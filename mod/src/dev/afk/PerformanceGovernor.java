package dev.afk;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;

/**
 * Keeps the game at 24 FPS or better. When frames drop it steps quality down one notch at a time
 * (render distance, entity distance, clouds, then shaders); when there is plenty of headroom it
 * steps back up, never past your own settings. Changes are only for this session: your settings
 * are put back when you leave the world or close the game.
 */
final class PerformanceGovernor {
    private static final int FLOOR_FPS = 24;
    private static final int HEADROOM_FPS = 48;
    private static final int MAX_LEVEL = 5;

    private boolean enabled = true;
    private boolean captured;
    private int baseRender;
    private double baseEntity;
    private CloudStatus baseClouds;
    private boolean baseShaders;
    private int level;
    private double avg = 60;
    private int lowSeconds;
    private int highSeconds;
    private int tick;
    private int graceSeconds = 15;

    boolean enabled() {
        return enabled;
    }

    void setEnabled(Minecraft mc, boolean on) {
        enabled = on;
        if (!on) restore(mc);
    }

    int level() {
        return level;
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (captured) restore(mc);
            captured = false;
            graceSeconds = 15;
            return;
        }
        if (!enabled) return;
        if (!captured) capture(mc);
        if (++tick % 20 != 0) return;
        if (graceSeconds > 0) {
            graceSeconds--;
            return;
        }
        if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) return;
        avg = avg * 0.6 + mc.getFps() * 0.4;
        lowSeconds = avg < FLOOR_FPS ? lowSeconds + 1 : 0;
        highSeconds = avg > HEADROOM_FPS ? highSeconds + 1 : 0;
        if (lowSeconds >= 4 && level < MAX_LEVEL) {
            apply(mc, level + 1);
            lowSeconds = 0;
            graceSeconds = 6;
        } else if (highSeconds >= 25 && level > 0) {
            apply(mc, level - 1);
            highSeconds = 0;
            graceSeconds = 10;
        }
    }

    private void capture(Minecraft mc) {
        baseRender = mc.options.renderDistance().get();
        baseEntity = mc.options.entityDistanceScaling().get();
        baseClouds = mc.options.cloudStatus().get();
        baseShaders = Iris.shadersOn();
        level = 0;
        captured = true;
    }

    private void apply(Minecraft mc, int newLevel) {
        int fps = (int) Math.round(avg);
        level = newLevel;
        int render = switch (level) {
            case 0 -> baseRender;
            case 1 -> baseRender - 2;
            case 2 -> baseRender - 4;
            case 3 -> baseRender - 4;
            case 4 -> 6;
            default -> 4;
        };
        mc.options.renderDistance().set(Math.max(2, Math.min(baseRender, render)));
        mc.options.entityDistanceScaling().set(level >= 2 ? Math.min(baseEntity, 0.75) : baseEntity);
        mc.options.cloudStatus().set(level >= 3 ? CloudStatus.OFF : baseClouds);
        if (baseShaders) Iris.setShaders(level < 3);
        String what = switch (level) {
            case 0 -> "full quality";
            case 1, 2 -> "render distance " + mc.options.renderDistance().get();
            case 3 -> baseShaders ? "shaders off" : "clouds off";
            default -> "render distance " + mc.options.renderDistance().get();
        };
        Afk.HUD.toast("PERFORMANCE", what + "  ·  " + fps + " fps");
        Afk.LOG.info("Performance level {} ({}; {} fps)", level, what, fps);
    }

    void restore(Minecraft mc) {
        if (!captured) return;
        mc.options.renderDistance().set(baseRender);
        mc.options.entityDistanceScaling().set(baseEntity);
        mc.options.cloudStatus().set(baseClouds);
        if (baseShaders && !Iris.shadersOn()) Iris.setShaders(true);
        level = 0;
    }

    /** Iris calls live here so the class loads fine without Iris installed. */
    private static final class Iris {
        static boolean present() {
            return FabricLoader.getInstance().isModLoaded("iris");
        }

        static boolean shadersOn() {
            return present() && IrisCalls.on();
        }

        static void setShaders(boolean on) {
            if (present()) IrisCalls.set(on);
        }
    }

    private static final class IrisCalls {
        static boolean on() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().areShadersEnabled();
        }

        static void set(boolean on) {
            net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().setShadersEnabledAndApply(on);
        }
    }
}
