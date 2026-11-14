package dev.afk;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Who is driving: the human or Herobrine. Also owns the two keys:
 *   Tab (tap)  hand your body to Herobrine, or take it back. Holding Tab still shows the player list.
 *   B          cinematic camera while you play (GTA style), on or off.
 */
final class AgentMode {
    private static final int TAP_TICKS = 6; // 0.3 s: longer than this is a "hold" for the player list

    private KeyMapping takeoverKey;
    private KeyMapping cinematicKey;
    private int heldTicks = -1;

    private volatile boolean agent;
    private volatile long taskId;
    private volatile String task;
    private volatile long changedAtMs = System.currentTimeMillis();

    void registerKeys() {
        KeyMapping.Category category = KeyMapping.Category.register(Afk.id("main"));
        takeoverKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.afk.takeover", InputConstants.Type.KEYBOARD, InputConstants.KEY_TAB, category));
        cinematicKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.afk.cinematic", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, category));
    }

    boolean isAgent() {
        return agent;
    }

    long changedAtMs() {
        return changedAtMs;
    }

    String task() {
        return task;
    }

    void tick(Minecraft mc) {
        if (mc.player == null) {
            if (agent) setAgent(false, "left the world");
            heldTicks = -1;
            return;
        }
        while (cinematicKey.consumeClick()) {
            if (mc.gui.screen() == null && !agent) Afk.CAMERA.toggleManual(mc);
        }
        int clicks = 0;
        while (takeoverKey.consumeClick()) clicks++;
        if (mc.gui.screen() != null) {
            heldTicks = -1;
            return;
        }
        boolean down = takeoverKey.isDown();
        if (down) {
            heldTicks = heldTicks < 0 ? 0 : heldTicks + 1;
        } else if (heldTicks >= 0) {
            if (heldTicks <= TAP_TICKS) toggle();
            heldTicks = -1;
        } else if (clicks > 0) {
            toggle(); // a tap shorter than one tick
        }
    }

    /** True while Tab is down but not yet held long enough to count as "show the player list". */
    boolean suppressPlayerList() {
        Minecraft mc = Minecraft.getInstance();
        boolean sameKey = KeyMappingHelper.getBoundKeyOf(takeoverKey).equals(KeyMappingHelper.getBoundKeyOf(mc.options.keyPlayerList));
        return sameKey && heldTicks >= 0 && heldTicks <= TAP_TICKS;
    }

    void toggle() {
        setAgent(!agent, agent ? "you pressed Tab" : "you pressed Tab");
    }

    /** Must run on the client thread. */
    void setAgent(boolean on, String why) {
        if (agent == on) return;
        Minecraft mc = Minecraft.getInstance();
        if (on && mc.player == null) return;
        agent = on;
        changedAtMs = System.currentTimeMillis();
        if (on) {
            Afk.ACTIVITY.handover(mc);
            Afk.BARITONE.onAgentStart();
            Afk.CAMERA.startAgent(mc);
            Afk.HUD.toast("HEROBRINE HAS CONTROL", "");
            Afk.EVENTS.add("mode", "the human handed you control (" + why + ")");
        } else {
            Afk.BARITONE.onAgentStop();
            Afk.CAMERA.stopAgent();
            Afk.ACTIVITY.beginPlayerSession(mc);
            Afk.HUD.toast("YOU HAVE CONTROL", "");
            Afk.EVENTS.add("mode", "the human took control back (" + why + ")");
        }
        Afk.ACTIVITY.save();
    }

    /** A plain-words task from /do, chat or the crafting box. Hands control to Herobrine if asked. */
    void setTask(String text, boolean takeControl) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) return;
        task = clean.length() > 300 ? clean.substring(0, 300) : clean;
        taskId++;
        Afk.EVENTS.add("task", task);
        Afk.HUD.toast("NEW TASK", task);
        if (takeControl) setAgent(true, "you gave a task");
    }

    void clearTask() {
        task = null;
        taskId++;
        Afk.EVENTS.add("task", "(task cleared)");
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", agent ? "agent" : "player");
        o.addProperty("secondsInMode", (System.currentTimeMillis() - changedAtMs) / 1000);
        if (task != null) {
            JsonObject t = new JsonObject();
            t.addProperty("id", taskId);
            t.addProperty("text", task);
            o.add("task", t);
        } else {
            o.addProperty("taskId", taskId);
        }
        return o;
    }
}
