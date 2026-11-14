package dev.afk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Starts Herobrine's brain (gameDir/afk/brain/agent.mjs, run with Node) when you join a world,
 * using the sign-in you chose: your Claude account through Claude Code, or your ChatGPT account
 * through Codex. Settings live in config/afk.json. The brain stops when the game closes.
 */
final class BrainLauncher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Process process;
    private long joinedAtMs;
    private long lastLaunchMs;
    private int failures;
    private boolean launched;

    boolean hasLaunched() {
        return launched;
    }

    private Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("afk.json");
    }

    JsonObject config() {
        try {
            return JsonParser.parseString(Files.readString(configFile(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return defaults();
        }
    }

    void saveConfig(JsonObject o) {
        try {
            Files.createDirectories(configFile().getParent());
            Files.writeString(configFile(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Afk.LOG.warn("Could not save config/afk.json", e);
        }
    }

    private JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("autoStartBrain", true);
        o.addProperty("provider", "claude");
        o.addProperty("model", "");
        o.addProperty("node", findBinary("node", List.of("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")));
        o.addProperty("brainScript", Afk.dataDir().resolve("brain").resolve("agent.mjs").toString());
        return o;
    }

    void ensureConfig() {
        if (!Files.exists(configFile())) saveConfig(defaults());
    }

    void tick(Minecraft mc) {
        if (mc.player == null) {
            joinedAtMs = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (joinedAtMs == 0) joinedAtMs = now;
        boolean alive = process != null && process.isAlive();
        boolean brainTalking = now - BridgeServer.lastBrainContactMs() < 8000;
        if (alive || brainTalking || now - joinedAtMs < 5000) return;
        long backoff = Math.min(300_000, 20_000L * (1L << Math.min(4, failures)));
        if (now - lastLaunchMs < backoff) return;
        lastLaunchMs = now;
        launch();
    }

    void launch() {
        JsonObject cfg = config();
        if (cfg.has("autoStartBrain") && !cfg.get("autoStartBrain").getAsBoolean()) return;
        String node = str(cfg, "node", "node");
        String script = str(cfg, "brainScript", Afk.dataDir().resolve("brain").resolve("agent.mjs").toString());
        if (!new File(script).isFile()) {
            Afk.EVENTS.add("bridge", "brain script not found at " + script);
            Afk.LOG.warn("Herobrine's brain was not found at {}", script);
            failures++;
            return;
        }
        try {
            Path dir = Path.of(script).getParent();
            ProcessBuilder pb = new ProcessBuilder(node, script).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("brain-console.log").toFile()));
            Map<String, String> env = pb.environment();
            String home = System.getProperty("user.home");
            env.put("PATH", String.join(File.pathSeparator, "/opt/homebrew/bin", "/usr/local/bin", home + "/.local/bin",
                    home + "/.npm-global/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin", env.getOrDefault("PATH", "")));
            env.put("AFK_AUTOSTARTED", "1");
            env.put("AFK_BRIDGE_URL", "http://127.0.0.1:" + Afk.PORT);
            env.put("AFK_PROVIDER", str(cfg, "provider", "claude"));
            String model = str(cfg, "model", "");
            if (!model.isBlank()) env.put("AFK_MODEL", model);
            process = pb.start();
            launched = true;
            failures = 0;
            Afk.LOG.info("Started Herobrine's brain ({}): {} {}", str(cfg, "provider", "claude"), node, script);
        } catch (Exception e) {
            failures++;
            Afk.EVENTS.add("bridge", "could not start the brain: " + e.getMessage());
            Afk.LOG.warn("Could not start Herobrine's brain", e);
        }
    }

    void restart() {
        stop();
        lastLaunchMs = 0;
        failures = 0;
        launch();
    }

    void stop() {
        Process p = process;
        process = null;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    /** Opens a Terminal window running the provider's sign-in, which then opens your browser. */
    static String login(String provider) {
        String cmd = provider.equals("codex") ? "codex login" : "claude auth login";
        String install = provider.equals("codex") ? "npm install -g @openai/codex" : "curl -fsSL https://claude.ai/install.sh | bash";
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return "Run this in a terminal: " + cmd + "  (install first if needed: " + install + ")";
        String bin = provider.equals("codex") ? "codex" : "claude";
        String script = "command -v " + bin + " >/dev/null 2>&1 || " + install + "; " + cmd;
        try {
            new ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to activate",
                    "-e", "tell application \"Terminal\" to do script \"" + script.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").start();
            return "A Terminal window is open to sign in to " + (provider.equals("codex") ? "Codex (your ChatGPT account)" : "Claude") + ". Finish in your browser.";
        } catch (Exception e) {
            return "Couldn't open Terminal. Run: " + cmd;
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static String findBinary(String name, List<String> candidates) {
        for (String c : candidates) if (new File(c).canExecute()) return c;
        return name;
    }
}
