package dev.afk;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;

/**
 * Test-only controls, reachable at POST /dev and only when the game is started with -Dafk.dev=true
 * (the offline test client does this). They stand in for a keyboard and mouse so the mod can be tested by script.
 */
final class DevHooks {
    private DevHooks() {
    }

    static JsonObject handle(HttpExchange exchange, JsonObject body) throws Exception {
        String action = body.has("action") ? body.get("action").getAsString() : "";
        String result = BridgeServer.onClientThread(() -> run(action, body));
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("result", result);
        return out;
    }

    private static String run(String action, JsonObject body) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        switch (action) {
            case "screen" -> {
                if (screen == null) return "none";
                List<String> buttons = new ArrayList<>();
                for (AbstractWidget w : Screens.getWidgets(screen)) if (w instanceof AbstractButton) buttons.add(w.getMessage().getString());
                return screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\" buttons=" + buttons;
            }
            case "click" -> {
                if (screen == null) throw new IllegalStateException("no screen open");
                String label = body.get("label").getAsString().toLowerCase(Locale.ROOT);
                for (AbstractWidget w : Screens.getWidgets(screen)) {
                    if (w instanceof AbstractButton button && w.getMessage().getString().toLowerCase(Locale.ROOT).contains(label)) {
                        button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                        return "pressed " + w.getMessage().getString();
                    }
                }
                throw new IllegalArgumentException("no button containing: " + label);
            }
            default -> { }
        }
        LocalPlayer p = mc.player;
        if (p == null) throw new IllegalStateException("not in a world");
        switch (action) {
            case "command" -> p.connection.sendCommand(body.get("command").getAsString().replaceFirst("^/+", ""));
            case "tab" -> Afk.MODE.toggle();
            case "cinematic" -> Afk.CAMERA.toggleManual(mc);
            case "moment" -> Afk.CAMERA.moment(body.get("label").getAsString(), body.has("title") ? body.get("title").getAsString() : "", 5.0);
            case "chat" -> {
                String text = body.get("text").getAsString();
                String forHerobrine = ChatAgent.addressedText(text);
                if (forHerobrine == null) return "would go to the server: " + text;
                Afk.CHAT.route(forHerobrine, "chat");
            }
            case "do" -> {
                Afk.MODE.setTask(body.get("text").getAsString(), true);
                Afk.CHAT.route(body.get("text").getAsString(), "/do");
            }
            case "look" -> {
                p.setYRot(body.get("yaw").getAsFloat());
                p.setXRot(body.get("pitch").getAsFloat());
            }
            case "key" -> { // press or release a real key binding, exactly as a keyboard event would
                int code = "b".equals(body.get("key").getAsString()) ? InputConstants.KEY_B : InputConstants.KEY_TAB;
                InputConstants.Key key = InputConstants.Type.KEYBOARD.getOrCreate(code);
                boolean down = body.get("down").getAsBoolean();
                net.minecraft.client.KeyMapping.set(key, down);
                if (down) net.minecraft.client.KeyMapping.click(key);
            }
            case "tap" -> { // press a key and release it "ms" later from inside the game, like a real keyboard
                int code = "b".equals(body.get("key").getAsString()) ? InputConstants.KEY_B : InputConstants.KEY_TAB;
                InputConstants.Key key = InputConstants.Type.KEYBOARD.getOrCreate(code);
                long ms = body.has("ms") ? body.get("ms").getAsLong() : 100;
                net.minecraft.client.KeyMapping.set(key, true);
                net.minecraft.client.KeyMapping.click(key);
                java.util.concurrent.CompletableFuture.delayedExecutor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> mc.execute(() -> net.minecraft.client.KeyMapping.set(key, false)));
            }
            case "inventory" -> mc.gui.setScreen(new InventoryScreen(p));
            case "close" -> p.closeContainer();
            case "box" -> {
                if (screen == null) throw new IllegalStateException("no screen open");
                Afk.CONTAINERS.handleText(mc, screen, body.get("text").getAsString());
            }
            default -> throw new IllegalArgumentException("unknown dev action: " + action);
        }
        return "done";
    }
}
