package dev.afk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Lets the performance governor move the active shader pack between the quality profiles the pack
 * itself defines (for example Complementary's Potato ... Ultra, BSL's Minimum ... Ultra, Photon's
 * low ... ultra). Nothing in the pack is modified: this only chooses among its own settings, the
 * same as picking a profile in the shader options screen, and your settings are put back afterwards.
 */
final class ShaderProfiles {
    record Profile(String name, Map<String, String> options) {}

    static final class Pack {
        String name;
        List<Profile> profiles = new ArrayList<>();
        /** Index of the profile closest to the settings you had when the world was joined. */
        int baseline;
        /** Your own option overrides, exactly as Iris had them saved. */
        Properties original = new Properties();
    }

    private static final Pattern DEFINE = Pattern.compile("^\\s*(//+)?\\s*#define\\s+(\\w+)(?:\\s+([^\\s/]+))?");
    private static final Pattern CONST = Pattern.compile("^\\s*const\\s+\\w+\\s+(\\w+)\\s*=\\s*([^;\\s]+)\\s*;");

    private ShaderProfiles() {
    }

    static boolean irisPresent() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    static boolean shadersOn() {
        try {
            return irisPresent() && IrisCalls.enabled();
        } catch (Throwable t) {
            return false;
        }
    }

    static void setShaders(boolean on) {
        try {
            if (irisPresent()) IrisCalls.setEnabled(on);
        } catch (Throwable t) {
            Afk.LOG.warn("Could not switch shaders", t);
        }
    }

    /** The active pack's profiles, lowest quality first, or null if there is nothing to step through. */
    static Pack load() {
        if (!shadersOn()) return null;
        try {
            String name = IrisCalls.packName();
            Path dir = IrisCalls.packsDir();
            if (name == null || name.isBlank() || dir == null) return null;
            Path packPath = dir.resolve(name);
            if (!Files.exists(packPath)) return null;
            Pack pack = new Pack();
            pack.name = name;

            Map<String, String> raw = new LinkedHashMap<>();
            Map<String, String> defaults = new HashMap<>();
            read(packPath, raw, null);
            if (raw.size() < 2) return null;
            for (String profile : raw.keySet()) {
                pack.profiles.add(new Profile(profile, resolve(profile, raw, 0)));
            }
            Set<String> keys = new java.util.HashSet<>();
            for (Profile p : pack.profiles) keys.addAll(p.options.keySet());
            read(packPath, null, new Object[] {keys, defaults});

            Path saved = dir.resolve(name + ".txt");
            if (Files.exists(saved)) {
                try (InputStream in = Files.newInputStream(saved)) {
                    pack.original.load(in);
                }
            }
            Map<String, String> current = new HashMap<>(defaults);
            for (String key : pack.original.stringPropertyNames()) current.put(key, pack.original.getProperty(key));

            int best = pack.profiles.size() - 1;
            int bestScore = -1;
            for (int i = 0; i < pack.profiles.size(); i++) {
                int score = 0;
                for (Map.Entry<String, String> e : pack.profiles.get(i).options.entrySet()) {
                    if (same(e.getValue(), current.get(e.getKey()))) score++;
                }
                if (score >= bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            pack.baseline = best;
            Afk.LOG.info("Shader pack {}: {} quality profiles, yours looks like {}", name, pack.profiles.size(), pack.profiles.get(best).name);
            return pack;
        } catch (Throwable t) {
            Afk.LOG.warn("Could not read the shader pack's profiles", t);
            return null;
        }
    }

    /** Switches the pack to one of its profiles (one shader reload). */
    static boolean apply(Pack pack, int index) {
        try {
            Properties options = new Properties();
            options.putAll(pack.original);
            options.putAll(pack.profiles.get(index).options);
            write(pack, options);
            IrisCalls.setEnabled(true); // reloads the pack, which reads the settings file
            return true;
        } catch (Throwable t) {
            Afk.LOG.warn("Could not switch the shader profile", t);
            return false;
        }
    }

    /** Puts your own shader settings back (one shader reload if shaders are on). */
    static void restore(Pack pack, boolean reloadNow) {
        try {
            write(pack, pack.original);
            if (reloadNow && shadersOn()) IrisCalls.setEnabled(true); // one reload, reading your settings back
        } catch (Throwable t) {
            Afk.LOG.warn("Could not restore your shader settings", t);
        }
    }

    /** Writes a pack's settings file without reloading (used to repair settings after a crash). */
    static void writeSaved(String packName, Properties options) {
        try {
            if (!irisPresent()) return;
            Pack pack = new Pack();
            pack.name = packName;
            write(pack, options);
        } catch (Throwable t) {
            Afk.LOG.warn("Could not put back the shader pack's settings", t);
        }
    }

    /** Iris keeps a pack's changed options in "<pack>.txt" beside it and reads that file on every reload. */
    private static void write(Pack pack, Properties options) throws IOException {
        Path saved = IrisCalls.packsDir().resolve(pack.name + ".txt");
        if (options.isEmpty()) {
            Files.deleteIfExists(saved);
            return;
        }
        try (var out = Files.newOutputStream(saved)) {
            options.store(out, "This file stores the shaderpack configuration for the shaderpack " + pack.name);
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Reads either the raw profile lines (profiles != null) or the default values of some option names. */
    @SuppressWarnings("unchecked")
    private static void read(Path packPath, Map<String, String> profiles, Object[] defaultsRequest) throws IOException {
        if (Files.isDirectory(packPath)) {
            try (Stream<Path> files = Files.walk(packPath)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String rel = packPath.relativize(file).toString().replace('\\', '/');
                    if (!wanted(rel, profiles != null)) continue;
                    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
                        scan(r, profiles, defaultsRequest);
                    }
                }
            }
            return;
        }
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !wanted(entry.getName(), profiles != null)) continue;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.ISO_8859_1))) {
                    scan(r, profiles, defaultsRequest);
                }
            }
        }
    }

    private static boolean wanted(String name, boolean profileFile) {
        if (profileFile) return name.endsWith("shaders/shaders.properties");
        return name.endsWith(".glsl") || name.endsWith(".fsh") || name.endsWith(".vsh") || name.endsWith(".gsh") || name.endsWith(".csh");
    }

    @SuppressWarnings("unchecked")
    private static void scan(BufferedReader reader, Map<String, String> profiles, Object[] defaultsRequest) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (profiles != null) {
                String t = line.strip();
                if (!t.startsWith("profile.")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                profiles.put(t.substring("profile.".length(), eq).strip(), t.substring(eq + 1).strip());
                continue;
            }
            Set<String> keys = (Set<String>) defaultsRequest[0];
            Map<String, String> defaults = (Map<String, String>) defaultsRequest[1];
            Matcher d = DEFINE.matcher(line);
            if (d.find() && keys.contains(d.group(2)) && !defaults.containsKey(d.group(2))) {
                boolean commented = d.group(1) != null;
                String value = d.group(3);
                defaults.put(d.group(2), value != null && !commented ? value : String.valueOf(!commented));
                continue;
            }
            Matcher c = CONST.matcher(line);
            if (c.find() && keys.contains(c.group(1)) && !defaults.containsKey(c.group(1))) defaults.put(c.group(1), c.group(2));
        }
    }

    /** Expands "profile.OTHER", "!FLAG", "FLAG" and "KEY=value" tokens into option values. */
    private static Map<String, String> resolve(String profile, Map<String, String> raw, int depth) {
        Map<String, String> out = new LinkedHashMap<>();
        String line = raw.get(profile);
        if (line == null || depth > 8) return out;
        for (String token : line.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("profile.")) out.putAll(resolve(token.substring("profile.".length()), raw, depth + 1));
            else if (token.startsWith("!")) out.put(token.substring(1), "false");
            else if (token.contains("=")) out.put(token.substring(0, token.indexOf('=')), token.substring(token.indexOf('=') + 1));
            else out.put(token, "true");
        }
        return out;
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        try {
            return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 1e-6;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Everything that touches Iris classes, so this file loads fine without Iris installed. */
    private static final class IrisCalls {
        static boolean enabled() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().areShadersEnabled();
        }

        static void setEnabled(boolean on) {
            net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().setShadersEnabledAndApply(on);
        }

        static String packName() {
            return net.irisshaders.iris.Iris.getCurrentPackName();
        }

        static Path packsDir() {
            return net.irisshaders.iris.Iris.getShaderpacksDirectory();
        }
    }
}
