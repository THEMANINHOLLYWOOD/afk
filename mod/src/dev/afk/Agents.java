package dev.afk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Herobrine's crew: helpers with their own bodies, called with /agent. They only exist in a world
 * running on this computer (singleplayer or Open to LAN), because a server or Realm decides for
 * itself what lives in its world. Up to five at once. Everything the crew does is reported here for
 * the crew brain, and Stop cancels every job and loop at once.
 */
final class Agents {
    static final int MAX = 5;
    static EntityType<Helper> TYPE;
    private static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, Afk.id("herobrine"));

    private static final List<JsonObject> events = new ArrayList<>();
    private static long lastEventId;
    private static volatile JsonArray snapshot = new JsonArray();
    private static volatile List<String> hudLines = List.of();
    private static volatile long stoppedAtMs;

    private Agents() {
    }

    static void register() {
        TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
                EntityType.Builder.<Helper>of(Helper::new, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).build(KEY));
        FabricDefaultAttributeRegistry.register(TYPE, Helper.attributes());
        EntityRendererRegistry.register(TYPE, HelperRenderer::new);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) refresh(server);
        });
    }

    // ------------------------------------------------------------------ where they can exist

    static IntegratedServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    static boolean available() {
        return server() != null;
    }

    static String whyNot() {
        return "Helpers need a world running on this computer (singleplayer or Open to LAN). On a server or Realm I can't add bodies to the world.";
    }

    private static <T> T onServer(Supplier<T> fn) throws Exception {
        IntegratedServer s = server();
        if (s == null) throw new IllegalStateException(whyNot());
        return s.submit(fn).get(10, TimeUnit.SECONDS);
    }

    static List<Helper> all(MinecraftServer server) {
        List<Helper> out = new ArrayList<>();
        if (server == null || TYPE == null) return out;
        for (ServerLevel level : server.getAllLevels()) out.addAll(level.getEntities(TYPE, h -> h.isAlive()));
        out.sort((a, b) -> Integer.compare(a.number(), b.number()));
        return out;
    }

    // ------------------------------------------------------------------ commands

    /** Calls a new helper next to you. Returns what to tell the human. */
    static String spawn(String task, String by) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "Join a world first.";
        if (!available()) return whyNot();
        UUID you = mc.player.getUUID();
        try {
            return onServer(() -> {
                IntegratedServer server = server();
                ServerPlayer player = server.getPlayerList().getPlayer(you);
                if (player == null) return "I can't find you in the world.";
                List<Helper> crew = all(server);
                if (crew.size() >= MAX) return "That's the whole crew: " + MAX + " helpers. Dismiss one first (/agent dismiss <name>).";
                int n = 2;
                while (true) {
                    final int candidate = n;
                    if (crew.stream().noneMatch(h -> h.number() == candidate)) break;
                    n++;
                }
                ServerLevel level = (ServerLevel) player.level();
                BlockPos spot = freeSpotNear(level, player.blockPosition());
                if (spot == null) return "There's no room next to you for a helper.";
                if (!level.isPositionEntityTicking(spot)) {
                    // On a very busy computer the world can still be loading creatures around you; a helper
                    // added now would be stored away instead of appearing.
                    return "The world around you is still loading. Try again in a few seconds.";
                }
                Helper h = TYPE.create(level, EntitySpawnReason.MOB_SUMMONED);
                if (h == null) return "The helper didn't appear.";
                h.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                h.setYRot(player.getYRot() + 180);
                h.setup(you, n);
                boolean added = level.addFreshEntity(h);
                Afk.LOG.info("Called {} at {} (added: {})", h.displayName(), spot.toShortString(), added);
                if (!added) return "The helper couldn't be added to the world.";
                h.puff(level);
                String text = task == null || task.isBlank() ? null : task.strip();
                event("spawned", h.displayName(), (by == null ? "the human" : by) + " called you in" + (text == null ? " (no task yet: you follow the human)" : " for: " + text));
                if (text != null) event("task", h.displayName(), text);
                return h.displayName() + (text == null ? " is here. He'll follow you; tell him what to do with /agent tell " + roman(n) + " <task>." : " is here, on it: " + text);
            });
        } catch (Exception e) {
            return "Couldn't call a helper: " + e.getMessage();
        }
    }

    /** Stop: every helper drops his job and loops, and Herobrine gives you your body back. */
    static void stopEverything(String why) {
        stoppedAtMs = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Afk.MODE.clearTask();
            Afk.MODE.setAgent(false, why);
            Afk.HANDS.abortAll(why);
            try {
                Afk.BARITONE.execute("stop");
            } catch (Exception ignored) {
            }
            Afk.HUD.toast("STOPPED", "every agent stopped");
        });
        IntegratedServer s = server();
        if (s != null) {
            s.execute(() -> {
                for (Helper h : all(s)) h.stopWork("you pressed stop");
            });
        }
        event("stop", "all", "the human pressed Stop: every job and loop is cancelled. Give no new jobs until the human asks for something.");
    }

    static String dismiss(String who) {
        if (!available()) return whyNot();
        try {
            return onServer(() -> {
                List<Helper> gone = new ArrayList<>();
                for (Helper h : all(server())) {
                    if (who == null || who.equalsIgnoreCase("all") || matches(h, who)) gone.add(h);
                }
                if (gone.isEmpty()) return who == null || who.equalsIgnoreCase("all") ? "No helpers to dismiss." : "No helper called " + who + ".";
                List<String> names = new ArrayList<>();
                for (Helper h : gone) {
                    ServerLevel level = (ServerLevel) h.level();
                    h.dropAll(level);
                    h.puff(level);
                    names.add(h.displayName());
                    event("dismissed", h.displayName(), "the human dismissed you");
                    h.markDismissed();
                    h.discard();
                }
                return String.join(", ", names) + (names.size() == 1 ? " is" : " are") + " gone. Anything they carried is on the ground where they stood.";
            });
        } catch (Exception e) {
            return "Couldn't dismiss: " + e.getMessage();
        }
    }

    /** A plain-words instruction for one helper, or "all", from you or from another agent. */
    static String tell(String who, String text, String from) {
        if (!available()) return whyNot();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < snapshot.size(); i++) {
            JsonObject h = snapshot.get(i).getAsJsonObject();
            String name = h.get("name").getAsString();
            if (who == null || who.equalsIgnoreCase("all") || who.equalsIgnoreCase("crew") || matches(name, h.get("number").getAsInt(), who)) names.add(name);
        }
        if (names.isEmpty()) return snapshot.isEmpty() ? "No helpers yet. Call one with /agent." : "No helper called " + who + ".";
        for (String n : names) event(from == null ? "task" : "message", n, (from == null ? "" : from + " says: ") + text);
        return "Told " + String.join(", ", names) + ": " + text;
    }

    /** Applies a job from the crew brain. */
    static String assign(String who, JsonObject job, int repeat) throws Exception {
        if (System.currentTimeMillis() - stoppedAtMs < 4000) return "ignored: the human just pressed Stop";
        return onServer(() -> {
            for (Helper h : all(server())) {
                if (matches(h, who)) {
                    h.assign(job, repeat);
                    return "ok: " + h.displayName() + " is " + h.describeJob();
                }
            }
            return "no helper called " + who;
        });
    }

    static String list() {
        if (!available()) return whyNot();
        if (snapshot.isEmpty()) return "No helpers. Call one with /agent, or /agent <task>.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snapshot.size(); i++) {
            JsonObject h = snapshot.get(i).getAsJsonObject();
            if (sb.length() > 0) sb.append(" · ");
            sb.append(h.get("name").getAsString()).append(": ").append(h.get("status").getAsString());
        }
        return sb.toString();
    }

    static int count() {
        return snapshot.size();
    }

    // ------------------------------------------------------------------ reports, for the crew brain

    static void report(Helper h, String job, boolean ok, String result) {
        event("result", h.displayName(), job + (ok ? " finished: " : " failed: ") + result);
        String line = (ok ? "" : "Couldn't do it: ") + capital(result) + ".";
        Minecraft.getInstance().execute(() -> Afk.CHAT.showAs(h.displayName(), line));
    }

    static synchronized void event(String kind, String helper, String text) {
        JsonObject e = new JsonObject();
        e.addProperty("id", ++lastEventId);
        e.addProperty("atMs", System.currentTimeMillis());
        e.addProperty("kind", kind);
        e.addProperty("helper", helper);
        e.addProperty("text", text);
        events.add(e);
        while (events.size() > 200) events.remove(0);
    }

    static synchronized JsonObject since(long since) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        long now = System.currentTimeMillis();
        for (JsonObject e : events) {
            if (e.get("id").getAsLong() <= since) continue;
            JsonObject c = e.deepCopy();
            c.addProperty("secondsAgo", (now - c.get("atMs").getAsLong()) / 1000);
            c.remove("atMs");
            list.add(c);
        }
        out.add("events", list);
        out.addProperty("lastEventId", lastEventId);
        out.add("helpers", snapshot.deepCopy());
        out.addProperty("max", MAX);
        out.addProperty("available", available());
        return out;
    }

    static JsonArray snapshot() {
        return snapshot;
    }

    static List<String> hudLines() {
        return hudLines;
    }

    private static void refresh(MinecraftServer server) {
        Minecraft mc = Minecraft.getInstance();
        UUID you = mc.player == null ? null : mc.player.getUUID();
        ServerPlayer player = you == null ? null : server.getPlayerList().getPlayer(you);
        JsonArray arr = new JsonArray();
        List<String> lines = new ArrayList<>();
        for (Helper h : all(server)) {
            arr.add(h.toJson(player));
            lines.add(roman(h.number()) + "  " + h.status());
        }
        snapshot = arr;
        hudLines = lines;
    }

    // ------------------------------------------------------------------ small things

    /** A spot a body fits in next to "around": two blocks you can walk through (air, grass, flowers) over solid ground. */
    static BlockPos freeSpotNear(ServerLevel level, BlockPos around) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 1; dy >= -3; dy--) {
                        BlockPos p = around.offset(dx, dy, dz);
                        if (fits(level, p)) return p;
                    }
                }
            }
        }
        return around; // you're standing there, so he can too
    }

    /** Room for a body: two blocks you can walk through, over anything you can stand on (leaves count). */
    private static boolean fits(ServerLevel level, BlockPos p) {
        return passable(level, p) && passable(level, p.above())
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    private static boolean passable(ServerLevel level, BlockPos p) {
        var s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    static String roman(int n) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII"};
        return n >= 1 && n <= r.length ? r[n - 1] : String.valueOf(n);
    }

    private static boolean matches(Helper h, String who) {
        return matches(h.displayName(), h.number(), who);
    }

    private static boolean matches(String name, int number, String who) {
        if (who == null) return false;
        String w = who.strip().toLowerCase(Locale.ROOT).replace("herobrine", "").replace("#", "").strip();
        return w.equals(roman(number).toLowerCase(Locale.ROOT)) || w.equals(String.valueOf(number)) || name.equalsIgnoreCase(who.strip());
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
