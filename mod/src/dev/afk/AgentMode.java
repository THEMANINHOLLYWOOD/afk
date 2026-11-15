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
    private static final long TAP_MS = 300; // held longer than this is a "hold" for the player list...
    private static final long TAP_FRAMES = 2; // ...unless the game drew at most two frames meanwhile (lag)

    private KeyMapping takeoverKey;
    private KeyMapping cinematicKey;
    private long downAtNs = -1;
    private long downAtFrame;

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
        panelKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.afk.panel", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, category));
        stopKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.afk.stop", InputConstants.Type.KEYBOARD, InputConstants.KEY_BACKSPACE, category));
    }

    private KeyMapping panelKey;
    private KeyMapping stopKey;

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
            downAtNs = -1;
            return;
        }
        while (cinematicKey.consumeClick()) {
            if (mc.gui.screen() == null && !agent) Afk.CAMERA.toggleManual(mc);
        }
        while (panelKey.consumeClick()) {
            if (mc.gui.screen() == null) mc.gui.setScreen(new ControlPanel());
        }
        while (stopKey.consumeClick()) {
            if (mc.gui.screen() == null) Agents.stopEverything("you pressed Stop");
        }
        int clicks = 0;
        while (takeoverKey.consumeClick()) clicks++;
        if (mc.gui.screen() != null) {
            downAtNs = -1;
            return;
        }
        boolean down = takeoverKey.isDown();
        if (down) {
            if (downAtNs < 0) {
                downAtNs = System.nanoTime();
                downAtFrame = AfkHooks.frames;
            }
        } else if (downAtNs >= 0) {
            if (tapSoFar()) toggle();
            downAtNs = -1;
        } else if (clicks > 0) {
            toggle(); // pressed and released between two ticks
        }
    }

    /**
     * Still short enough to be a tap. Timed by the clock and by rendered frames, not by game ticks:
     * when the game lags it runs a burst of catch-up ticks, which made a quick tap look like a hold.
     */
    private boolean tapSoFar() {
        long ms = (System.nanoTime() - downAtNs) / 1_000_000L;
        long frames = AfkHooks.frames - downAtFrame;
        return ms <= TAP_MS || frames <= TAP_FRAMES;
    }

    /** True while Tab is down but not yet held long enough to count as "show the player list". */
    boolean suppressPlayerList() {
        Minecraft mc = Minecraft.getInstance();
        boolean sameKey = KeyMappingHelper.getBoundKeyOf(takeoverKey).equals(KeyMappingHelper.getBoundKeyOf(mc.options.keyPlayerList));
        return sameKey && downAtNs >= 0 && tapSoFar();
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
            String line = pick(BACK_TO_WORK);
            Afk.HUD.toast("HEROBRINE HAS CONTROL", "");
            Afk.HUD.quip(line);
            clank(mc, 0.75f);
            Afk.EVENTS.add("mode", "the human handed you control (" + why + "); you grumbled \"" + line + "\"");
        } else {
            Afk.HANDS.abortAll("the human took control");
            Afk.BARITONE.onAgentStop();
            Afk.CAMERA.stopAgent();
            Afk.ACTIVITY.beginPlayerSession(mc);
            Afk.HUD.toast("YOU HAVE CONTROL", pick(RETIRING));
            clank(mc, 1.25f);
            Afk.EVENTS.add("mode", "the human took control back (" + why + ")");
        }
        Afk.ACTIVITY.save();
    }

    // Herobrine is retired and keeps getting dragged back to work.
    private static final String[] BACK_TO_WORK = {
        "I was retired. Fine. One more job.",
        "Thirty years of stunts and you still need me. Alright.",
        "Back in the chains. What are we doing?",
        "I don't do this anymore. Hand me the pickaxe.",
        "Every time I sit down. Every single time.",
        "Okay, I'm up. Nobody touch my coffee.",
        "You know what, sure. Retirement was boring anyway.",
    };
    private static final String[] RETIRING = {
        "Back to the porch.",
        "Wake me when it's important.",
        "Your body, your problem. Don't fall in lava.",
        "Retired again. Feels good.",
        "I'll be around. I'm always around.",
    };
    private static final java.util.Random RANDOM = new java.util.Random();

    private static String pick(String[] lines) {
        return lines[RANDOM.nextInt(lines.length)];
    }

    /** The shackles: a chain rattle only you hear. */
    private static void clank(Minecraft mc, float pitch) {
        try {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.CHAIN_BREAK, pitch, 0.6f));
        } catch (Throwable ignored) {
        }
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
