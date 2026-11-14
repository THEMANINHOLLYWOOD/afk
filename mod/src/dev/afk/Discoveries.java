package dev.afk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Firsts worth a cinematic moment: a new biome, a creature you have never seen, the first of an
 * ore, a big cave, the Nether, the End. Remembered across sessions in gameDir/afk/discoveries.json.
 */
final class Discoveries {
    /** Creatures worth a camera move the first time; the rest get a title card only. */
    private static final Set<String> RARE = Set.of("warden", "ender_dragon", "wither", "elder_guardian", "ravager", "sniffer",
            "allay", "axolotl", "panda", "mooshroom", "ghast", "piglin_brute", "iron_golem", "camel", "armadillo", "creaking");

    private final Set<String> seen = new HashSet<>();
    private String lastBiome = "";
    private String lastDimension = "";
    private long lastCaveMs;
    private int tick;
    private boolean dirty;

    void registerEvents() {
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            String id = StateSampler.shortId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            if (StateSampler.isOre(id)) {
                String base = id.replace("deepslate_", "").replace("nether_", "");
                if (firstTime("ore:" + base)) Afk.CAMERA.moment("FIRST " + pretty(base.replace("_ore", "")), "", 5.0);
            }
        });
    }

    void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (++tick % 20 != 0) return;
        BlockPos pos = p.blockPosition();

        String dim = StateSampler.shortId(mc.level.dimension().identifier());
        if (!dim.equals(lastDimension)) {
            if (!lastDimension.isEmpty()) {
                if (dim.equals("the_nether") && firstTime("dim:nether")) Afk.CAMERA.moment("THE NETHER", "first time here", 6.0);
                else if (dim.equals("the_end") && firstTime("dim:end")) Afk.CAMERA.moment("THE END", "first time here", 7.0);
                else if (!dim.equals("overworld")) Afk.CAMERA.moment(pretty(dim), "", 4.0);
            }
            lastDimension = dim;
        }

        String biome = mc.level.getBiome(pos).getRegisteredName().replace("minecraft:", "");
        if (!biome.equals(lastBiome)) {
            boolean first = firstTime("biome:" + biome);
            if (!lastBiome.isEmpty() && first) Afk.CAMERA.moment("NEW BIOME", pretty(biome), 5.5);
            lastBiome = biome;
        }

        for (StateSampler.SeenMob mob : Afk.STATE.seenMobs()) {
            if (mob.entity() instanceof Player) continue;
            if (mob.distance() < 24 && firstTime("mob:" + mob.id())) {
                boolean rare = RARE.contains(mob.id());
                Afk.CAMERA.moment("NEW CREATURE", pretty(mob.id()), rare ? 6.0 : 4.5, rare);
                break;
            }
        }

        // A big cave: underground, dark sky above, lots of open air around.
        long now = System.currentTimeMillis();
        if (now - lastCaveMs > 10 * 60_000 && dim.equals("overworld")) {
            int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
            if (top > pos.getY() + 12 && openAir(mc, pos) > 0.55) {
                lastCaveMs = now;
                Afk.CAMERA.moment("CAVE", "y " + pos.getY(), 5.0);
            }
        }
    }

    private static double openAir(Minecraft mc, BlockPos c) {
        int air = 0;
        int total = 0;
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dy = -2; dy <= 6; dy += 2) {
                for (int dz = -6; dz <= 6; dz += 2) {
                    total++;
                    if (mc.level.getBlockState(c.offset(dx, dy, dz)).isAir()) air++;
                }
            }
        }
        return air / (double) total;
    }

    private boolean firstTime(String key) {
        if (seen.add(key)) {
            dirty = true;
            Afk.EVENTS.add("discovery", key);
            return true;
        }
        return false;
    }

    static String pretty(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.replace(':', ' ').replace('_', ' ').split(" ")) {
            if (w.isEmpty() || w.equals("minecraft")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return out.toString();
    }

    void save() {
        if (!dirty) return;
        try {
            Path dir = Afk.dataDir();
            Files.createDirectories(dir);
            JsonArray arr = new JsonArray();
            seen.forEach(arr::add);
            JsonObject o = new JsonObject();
            o.add("seen", arr);
            Files.writeString(dir.resolve("discoveries.json"), new Gson().toJson(o), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            Afk.LOG.warn("Could not save discoveries", e);
        }
    }

    void load() {
        try {
            Path file = Afk.dataDir().resolve("discoveries.json");
            if (!Files.exists(file)) return;
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement el : parsed.getAsJsonObject().getAsJsonArray("seen")) seen.add(el.getAsString());
        } catch (Exception e) {
            Afk.LOG.warn("Could not load discoveries", e);
        }
    }
}
