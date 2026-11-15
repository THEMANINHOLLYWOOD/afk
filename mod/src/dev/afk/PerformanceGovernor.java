package dev.afk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;

/**
 * Keeps the game at 24 FPS or better by walking a ladder, one rung at a time, and climbing back
 * when there is headroom:
 *
 *   your settings
 *   -> the shader pack's own lower quality profiles (Complementary's High, Medium, Low ... Potato)
 *   -> shorter render distance -> fewer far entities, no clouds
 *   -> shaders off -> render distance 6 -> render distance 4
 *
 * It never goes above your own settings, changes nothing permanently, and puts everything back when
 * you leave the world or close the game. Shader steps cost one reload hitch each, so they are taken
 * slowly, and a rung that keeps failing is retried less and less often.
 */
final class PerformanceGovernor {
    private static final int FLOOR_FPS = 24;
    private static final int HEADROOM_FPS = 50;

    /** One rung: profile -1 means "your own shader settings". */
    private record Rung(int profile, boolean shaders, int render, double entity, CloudStatus clouds, String label) {}

    private boolean enabled = true;
    private boolean captured;
    private int baseRender;
    private double baseEntity;
    private CloudStatus baseClouds;
    private boolean baseShaders;
    private ShaderProfiles.Pack pack;
    private final List<Rung> ladder = new ArrayList<>();
    private Rung applied;
    private int level;

    private double avg = 60;
    private int lowSeconds;
    private int highSeconds;
    private int tick;
    private int graceSeconds = 15;
    private long lastDownMs;
    private long lastUpMs;
    private int bounces;

    boolean enabled() {
        return enabled;
    }

    void setEnabled(Minecraft mc, boolean on) {
        enabled = on;
        if (!on) {
            restore(mc);
            captured = false;
        }
    }

    int level() {
        return level;
    }

    String describe() {
        if (!enabled) return "off";
        if (!captured || ladder.isEmpty()) return "on";
        return "on, " + ladder.get(level).label + " (step " + level + " of " + (ladder.size() - 1) + ", " + Math.round(avg) + " fps)";
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (captured) restore(mc);
            captured = false;
            graceSeconds = 15;
            return;
        }
        if (!enabled) return;
        if (++tick % 20 != 0) return;
        if (graceSeconds > 0) {
            graceSeconds--;
            return;
        }
        if (!captured) capture(mc);
        if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) return;

        int fps = mc.getFps();
        avg = avg * 0.6 + fps * 0.4;
        lowSeconds = avg < FLOOR_FPS ? lowSeconds + 1 : 0;
        highSeconds = avg > HEADROOM_FPS ? highSeconds + 1 : 0;
        long now = System.currentTimeMillis();

        if (lowSeconds >= 4 && level < ladder.size() - 1) {
            if (now - lastUpMs < 120_000) bounces = Math.min(4, bounces + 1); // that rung didn't hold
            apply(mc, level + 1);
            lastDownMs = now;
            lowSeconds = 0;
        } else if (level > 0 && highSeconds >= 30 && now - lastDownMs > (60_000L << bounces)) {
            apply(mc, level - 1);
            lastUpMs = now;
            highSeconds = 0;
        } else if (bounces > 0 && now - lastDownMs > 600_000 && now - lastUpMs > 600_000) {
            bounces = 0; // stable for ten minutes: forgive
        }
    }

    private void capture(Minecraft mc) {
        baseRender = mc.options.renderDistance().get();
        baseEntity = mc.options.entityDistanceScaling().get();
        baseClouds = mc.options.cloudStatus().get();
        baseShaders = ShaderProfiles.shadersOn();
        pack = baseShaders ? ShaderProfiles.load() : null;

        ladder.clear();
        Rung top = new Rung(-1, baseShaders, baseRender, baseEntity, baseClouds, "full quality");
        ladder.add(top);
        int profile = -1;
        if (pack != null) {
            for (int i = pack.baseline - 1; i >= 0; i--) {
                profile = i;
                ladder.add(new Rung(i, true, baseRender, baseEntity, baseClouds, "shaders " + pack.profiles.get(i).name().toLowerCase(Locale.ROOT)));
            }
        }
        int render = baseRender;
        if (render > 8) {
            render = Math.max(8, render - 4);
            ladder.add(new Rung(profile, baseShaders, render, baseEntity, baseClouds, "render distance " + render));
        }
        double entity = Math.min(baseEntity, 0.75);
        ladder.add(new Rung(profile, baseShaders, render, entity, CloudStatus.OFF, "fewer far entities, no clouds"));
        if (baseShaders) ladder.add(new Rung(profile, false, render, entity, CloudStatus.OFF, "shaders off"));
        if (render > 6) ladder.add(new Rung(profile, false, 6, entity, CloudStatus.OFF, "render distance 6"));
        if (render > 4) ladder.add(new Rung(profile, false, 4, entity, CloudStatus.OFF, "render distance 4"));

        applied = top;
        level = 0;
        avg = 60;
        captured = true;
    }

    private void apply(Minecraft mc, int newLevel) {
        Rung to = ladder.get(newLevel);
        if (level == 0 && newLevel > 0) savePending(); // remember your settings in case the game never gets to put them back
        boolean reload = set(mc, to, true);
        level = newLevel;
        if (newLevel == 0) clearPending();
        graceSeconds = reload ? 12 : 6;
        Afk.HUD.toast("PERFORMANCE", to.label + "  ·  " + Math.round(avg) + " fps");
        Afk.LOG.info("Performance step {} of {}: {} ({} fps)", newLevel, ladder.size() - 1, to.label, Math.round(avg));
    }

    /** Makes the game match a rung. Returns true if shaders had to reload. */
    private boolean set(Minecraft mc, Rung to, boolean inWorld) {
        mc.options.renderDistance().set(to.render);
        mc.options.entityDistanceScaling().set(to.entity);
        mc.options.cloudStatus().set(to.clouds);
        boolean reload = false;
        if (baseShaders && applied != null) {
            if (!to.shaders && applied.shaders) {
                ShaderProfiles.setShaders(false);
                reload = true;
            } else if (to.shaders && (!applied.shaders || to.profile != applied.profile)) {
                if (pack == null) ShaderProfiles.setShaders(true);
                else if (to.profile < 0) ShaderProfiles.restore(pack, true);
                else ShaderProfiles.apply(pack, to.profile);
                reload = true;
            }
        }
        applied = to;
        return reload;
    }

    /** Puts your own settings back. Safe to call any time. */
    void restore(Minecraft mc) {
        if (!captured || ladder.isEmpty()) return;
        boolean inWorld = mc.player != null;
        Rung top = ladder.get(0);
        mc.options.renderDistance().set(top.render);
        mc.options.entityDistanceScaling().set(top.entity);
        mc.options.cloudStatus().set(top.clouds);
        if (baseShaders && applied != null && (applied.profile != -1 || !applied.shaders)) {
            if (pack != null) ShaderProfiles.restore(pack, inWorld);
            if (!ShaderProfiles.shadersOn() || pack == null) ShaderProfiles.setShaders(true);
        }
        boolean wasLowered = level > 0;
        applied = top;
        level = 0;
        if (wasLowered) {
            mc.options.save(); // your own values are what ends up in options.txt
            clearPending();
        }
    }

    // ------------------------------------------------------------------ crash safety

    /** Your settings from before the ladder lowered anything; deleted again once they are back. */
    private static Path pendingFile() {
        return FabricLoader.getInstance().getGameDir().resolve("afk").resolve("perf-restore.json");
    }

    private void savePending() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("render", baseRender);
            o.addProperty("entity", baseEntity);
            o.addProperty("clouds", baseClouds.name());
            o.addProperty("shaders", baseShaders);
            if (pack != null) {
                o.addProperty("pack", pack.name);
                JsonObject options = new JsonObject();
                for (String key : pack.original.stringPropertyNames()) options.addProperty(key, pack.original.getProperty(key));
                o.add("packOptions", options);
            }
            Files.createDirectories(pendingFile().getParent());
            Files.writeString(pendingFile(), o.toString());
        } catch (Exception e) {
            Afk.LOG.warn("Could not note your graphics settings", e);
        }
    }

    private static void clearPending() {
        try {
            Files.deleteIfExists(pendingFile());
        } catch (Exception ignored) {
        }
    }

    /** If the game closed while settings were lowered (a crash or a force quit), puts yours back. */
    static void recoverAfterCrash(Minecraft mc) {
        Path file = pendingFile();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            mc.options.renderDistance().set(o.get("render").getAsInt());
            mc.options.entityDistanceScaling().set(o.get("entity").getAsDouble());
            mc.options.cloudStatus().set(CloudStatus.valueOf(o.get("clouds").getAsString()));
            mc.options.save();
            if (o.has("pack")) {
                Properties options = new Properties();
                for (var e : o.getAsJsonObject("packOptions").entrySet()) options.setProperty(e.getKey(), e.getValue().getAsString());
                ShaderProfiles.writeSaved(o.get("pack").getAsString(), options);
            }
            if (o.get("shaders").getAsBoolean()) ShaderProfiles.setShaders(true); // also reloads the pack with your settings
            Afk.LOG.info("Put back your graphics settings from before the game last closed unexpectedly");
        } catch (Exception e) {
            Afk.LOG.warn("Could not put back your graphics settings", e);
        } finally {
            clearPending();
        }
    }
}
