package dev.afk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;

/**
 * Loopback-only HTTP API for Herobrine's brain.
 *
 *   GET  /state?since=<id>       snapshot + events newer than <id>
 *   GET  /activity?since=<id>    what the human did while they had control
 *   POST /baritone {command}     one whitelisted, legit Baritone command (only while Herobrine has control)
 *   POST /thought  {id,kind,text} commentary for the HUD (streams: same id = update)
 *   POST /hud      {status,goal}
 *   POST /context  {plan,step,target,notes,context,home}
 *   POST /mode     {agent,why}    take or hand back control
 *   POST /say      {text}         a line from Herobrine in your chat (only you see it)
 *   POST /servercommand {command} run a server command you asked for in chat (only right after you asked)
 *   POST /craft    {item,count}   craft in the open crafting screen (for the human)
 *   POST /act      {actions:[{do,...}]}  hands: craft, use, place, eat, sleep, stash, take, close, equip, sort
 *   POST /respawn
 *   POST /screenshot             save a screenshot (used to check the visuals)
 */
final class BridgeServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static volatile HttpServer server;
    private static volatile boolean stopping;
    private static volatile long lastBrainContactMs;

    private interface Handler {
        JsonObject handle(HttpExchange exchange, JsonObject body) throws Exception;
    }

    static long lastBrainContactMs() {
        return lastBrainContactMs;
    }

    static void start(int port) {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
            created.setExecutor(Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "afk-bridge-http");
                thread.setDaemon(true);
                return thread;
            }));
            created.createContext("/state", guard(BridgeServer::state));
            created.createContext("/activity", guard(BridgeServer::activity));
            created.createContext("/crew", guard(BridgeServer::crew));
            created.createContext("/baritone", guard(BridgeServer::baritone));
            created.createContext("/thought", guard(BridgeServer::thought));
            created.createContext("/hud", guard(BridgeServer::hud));
            created.createContext("/context", guard(BridgeServer::context));
            created.createContext("/mode", guard(BridgeServer::mode));
            created.createContext("/say", guard(BridgeServer::say));
            created.createContext("/servercommand", guard(BridgeServer::serverCommand));
            created.createContext("/craft", guard(BridgeServer::craft));
            created.createContext("/act", guard(BridgeServer::act));
            created.createContext("/respawn", guard(BridgeServer::respawn));
            created.createContext("/screenshot", guard(BridgeServer::screenshot));
            if (Boolean.getBoolean("afk.dev")) created.createContext("/dev", guard(DevHooks::handle));
            created.start();
            server = created;
        } catch (IOException e) {
            Afk.LOG.error("AFK bridge could not listen on port {} (is another copy of the game open?)", port, e);
        }
    }

    /** The HTTP dispatcher thread is not a daemon; stop it or the game cannot exit. */
    static void stop() {
        stopping = true;
        HttpServer current = server;
        server = null;
        if (current != null) current.stop(0);
    }

    /**
     * Only a local program that sets our header may call. Browsers add an Origin header to
     * cross-site requests and cannot add custom headers without a preflight we never answer.
     */
    private static HttpHandler guard(Handler handler) {
        return exchange -> {
            int code = 200;
            JsonObject out;
            try {
                boolean local = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
                boolean fromBrowser = exchange.getRequestHeaders().getFirst("Origin") != null;
                boolean hasHeader = exchange.getRequestHeaders().getFirst("X-AFK-Bridge") != null;
                if (!local || fromBrowser || !hasHeader) {
                    code = 403;
                    out = error("forbidden");
                } else {
                    lastBrainContactMs = System.currentTimeMillis();
                    Afk.HUD.touch();
                    out = handler.handle(exchange, readBody(exchange));
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                code = 400;
                out = error(e.getMessage());
            } catch (Throwable t) {
                code = 500;
                out = error(String.valueOf(t));
                Afk.LOG.error("AFK bridge request failed", t);
            }
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    private static long since(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return 0;
        for (String pair : query.split("&")) {
            if (pair.startsWith("since=")) {
                try {
                    return Long.parseLong(pair.substring(6));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static JsonObject state(HttpExchange exchange, JsonObject body) {
        JsonObject out = Afk.EVENTS.since(since(exchange));
        out.add("state", JsonParser.parseString(Afk.STATE.json()));
        return out;
    }

    private static JsonObject activity(HttpExchange exchange, JsonObject body) {
        return Afk.ACTIVITY.since(since(exchange));
    }

    /**
     * GET  /crew?since=<id>   the helpers and what happened to them
     * POST /crew {"op": "spawn", "task": "...", "by": "..."}            call a helper (agents can call agents)
     *            {"op": "job", "helper": "II", "job": {...}, "repeat": s}  give a helper a job, optionally on a loop
     *            {"op": "say", "helper": "II", "text": "..."}             a helper talks in your chat
     *            {"op": "message", "from": "II", "to": "III", "text": "..."}  agents talking to agents
     *            {"op": "dismiss", "helper": "II"|"all"}   {"op": "stop"}
     */
    private static JsonObject crew(HttpExchange exchange, JsonObject body) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) return Agents.since(since(exchange));
        String op = string(body, "op");
        JsonObject out = ok();
        switch (op == null ? "" : op) {
            case "spawn" -> out.addProperty("result", Agents.spawn(string(body, "task"), string(body, "by")));
            case "job" -> {
                if (!body.has("job") || !body.get("job").isJsonObject()) throw new IllegalArgumentException("missing job");
                int repeat = body.has("repeat") ? body.get("repeat").getAsInt() : 0;
                out.addProperty("result", Agents.assign(string(body, "helper"), body.getAsJsonObject("job"), repeat));
            }
            case "say" -> {
                String who = string(body, "helper");
                String text = string(body, "text");
                if (text == null || text.isBlank()) throw new IllegalArgumentException("missing text");
                Minecraft.getInstance().execute(() -> Afk.CHAT.showAs(who == null ? "Herobrine" : who, text));
            }
            case "message" -> {
                String from = string(body, "from");
                String to = string(body, "to");
                String text = string(body, "text");
                if (text == null || text.isBlank()) throw new IllegalArgumentException("missing text");
                Minecraft.getInstance().execute(() -> Afk.CHAT.showAs((from == null ? "Herobrine" : from) + " → " + (to == null ? "crew" : to), text));
                if (to != null && (to.equalsIgnoreCase("herobrine") || to.equalsIgnoreCase("I"))) {
                    Afk.EVENTS.add("crew", (from == null ? "a helper" : from) + " says: " + text);
                } else {
                    Agents.tell(to == null ? "all" : to, text, from == null ? "Herobrine" : from);
                }
            }
            case "dismiss" -> out.addProperty("result", Agents.dismiss(string(body, "helper")));
            case "stop" -> Agents.stopEverything("the crew brain stopped everyone");
            default -> throw new IllegalArgumentException("unknown op: " + op);
        }
        return out;
    }

    private static JsonObject baritone(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        String command = BaritoneLink.validate(string(body, "command"));
        boolean recognised = onClientThread(() -> Afk.BARITONE.execute(command));
        JsonObject out = new JsonObject();
        out.addProperty("ok", recognised);
        out.addProperty("command", command);
        if (!recognised) out.addProperty("error", "Baritone did not recognise that command");
        return out;
    }

    private static JsonObject thought(HttpExchange exchange, JsonObject body) {
        requirePost(exchange);
        JsonElement id = body.get("id");
        if (id == null || !id.isJsonPrimitive()) throw new IllegalArgumentException("missing id");
        Afk.HUD.entry(id.getAsLong(), string(body, "kind"), string(body, "text"));
        return ok();
    }

    private static JsonObject hud(HttpExchange exchange, JsonObject body) {
        requirePost(exchange);
        Afk.HUD.setStatus(string(body, "status"), string(body, "goal"));
        return ok();
    }

    private static JsonObject context(HttpExchange exchange, JsonObject body) {
        requirePost(exchange);
        Afk.HUD.setContext(body);
        return ok();
    }

    private static JsonObject mode(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        boolean agent = body.has("agent") && body.get("agent").getAsBoolean();
        String why = string(body, "why");
        onClientThread(() -> {
            Afk.MODE.setAgent(agent, why == null ? "requested" : why);
            return true;
        });
        JsonObject out = ok();
        out.addProperty("mode", Afk.MODE.isAgent() ? "agent" : "player");
        return out;
    }

    private static JsonObject say(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        String text = string(body, "text");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("missing text");
        onClientThread(() -> {
            Afk.CHAT.showHerobrine(text);
            return true;
        });
        return ok();
    }

    private static JsonObject serverCommand(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        String command = string(body, "command");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("missing command");
        if (System.currentTimeMillis() - Afk.CHAT.lastHumanRequestMs() > 120_000) {
            throw new IllegalStateException("server commands only run right after the human asked for one in chat");
        }
        String clean = command.strip().replaceFirst("^/+", "");
        if (clean.length() > 240 || clean.contains("\n")) throw new IllegalArgumentException("bad command");
        onClientThread(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) throw new IllegalStateException("not in a world");
            p.connection.sendCommand(clean);
            Afk.CHAT.showHerobrine("ran /" + clean);
            return true;
        });
        return ok();
    }

    private static JsonObject craft(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        String item = string(body, "item");
        int count = body.has("count") ? Math.max(1, Math.min(640, body.get("count").getAsInt())) : 1;
        if (item == null || item.isBlank()) throw new IllegalArgumentException("missing item");
        String result = onClientThread(() -> Afk.CONTAINERS.craftById(item, count));
        JsonObject out = ok();
        out.addProperty("result", result);
        return out;
    }

    private static JsonObject act(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        if (!body.has("actions") || !body.get("actions").isJsonArray()) throw new IllegalArgumentException("missing \"actions\" array");
        String queued = onClientThread(() -> {
            if (!Afk.MODE.isAgent()) throw new IllegalStateException("the human has control right now");
            return Afk.HANDS.enqueue(body.getAsJsonArray("actions"));
        });
        JsonObject out = ok();
        out.addProperty("result", queued);
        return out;
    }

    private static JsonObject respawn(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        boolean respawned = onClientThread(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || !player.isDeadOrDying()) return false;
            player.respawn();
            return true;
        });
        JsonObject out = ok();
        out.addProperty("respawned", respawned);
        return out;
    }

    private static JsonObject screenshot(HttpExchange exchange, JsonObject body) throws Exception {
        requirePost(exchange);
        onClientThread(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), message -> Afk.LOG.info("Screenshot: {}", message.getString()));
            return true;
        });
        JsonObject out = ok();
        out.addProperty("dir", Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").toString());
        return out;
    }

    static <T> T onClientThread(Supplier<T> work) throws Exception {
        if (stopping) throw new IllegalStateException("the game is closing");
        CompletableFuture<T> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(20, TimeUnit.SECONDS); // generous: the game can stall for seconds when it loads chunks
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw e;
        }
    }

    private static void requirePost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("use POST");
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
            if (bytes.length == 0) return new JsonObject();
            try {
                JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
                return parsed.getAsJsonObject();
            } catch (com.google.gson.JsonParseException e) {
                throw new IllegalArgumentException("body is not valid JSON");
            }
        }
    }

    private static String string(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static JsonObject ok() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message);
        return out;
    }
}
