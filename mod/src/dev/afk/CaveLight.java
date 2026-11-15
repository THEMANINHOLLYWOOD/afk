package dev.afk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Shader packs mostly ignore the game's Brightness slider, so with shaders on, caves and unlit
 * tunnels go nearly black. AFK turns each supported pack's own cave or minimum light setting up to
 * its maximum, once per pack (an unlit stone wall with Complementary measured 11/255 at the
 * default and 44/255 at the maximum). It is an ordinary option in the pack's settings screen:
 * change it back there and AFK leaves it alone, and an option you already changed is never touched.
 */
final class CaveLight {
    private record Rule(String packPrefix, String option, String value) {}

    private static final List<Rule> RULES = List.of(
            new Rule("complementary", "CAVE_LIGHTING", "1600"), // Complementary Unbound and Reimagined: default 100, max 1600
            new Rule("bsl", "MINLIGHT_I", "1.00"), // BSL minimum light: default 0.50, max 1.00
            new Rule("photon", "CAVE_LIGHTING_I", "2.00")); // Photon cave lighting: default 1.00, max 2.00

    private CaveLight() {
    }

    static void applyOnce() {
        try {
            String pack = ShaderProfiles.configuredPackName();
            if (pack == null || pack.isBlank()) return;
            Rule rule = rule(pack);
            if (rule == null) return;
            Path flag = Afk.dataDir().resolve("cave-light-applied");
            if (Files.exists(flag) && Files.readAllLines(flag).contains(pack)) return;
            boolean changed = ShaderProfiles.setOption(pack, rule.option, rule.value, true);
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, pack + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!changed) return;
            if (ShaderProfiles.shadersOn()) ShaderProfiles.setShaders(true); // reload so it shows now
            Afk.LOG.info("Brighter caves for {}: {}={}", pack, rule.option, rule.value);
        } catch (Exception e) {
            Afk.LOG.warn("Could not brighten caves", e);
        }
    }

    private static Rule rule(String pack) {
        String name = pack.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            if (name.startsWith(rule.packPrefix)) return rule;
        }
        return null;
    }
}
