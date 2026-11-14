package dev.afk;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Talk to Herobrine in plain words through the normal chat box (or macOS Dictation into it).
 *  - While Herobrine has control, everything you type goes to him. Start with "!" to talk to the server instead.
 *  - While you play, start with "hb", "herobrine" or "@hb" ("hb mine some iron", "herobrine, what did you do?").
 * Messages to Herobrine never leave your computer except to your own Claude/Codex sign-in.
 */
final class ChatAgent {
    private static final Pattern ADDRESSED = Pattern.compile("^@?(hb|herobrine)\\b[,:]?\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int EMBER = 0xFF8A3D;
    private static final int INK = 0xE9E1CE;
    private static final int DIM = 0x8C8676;

    private volatile long lastHumanRequestMs;

    long lastHumanRequestMs() {
        return lastHumanRequestMs;
    }

    void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String forHerobrine = addressedText(message);
            if (forHerobrine == null) return true;
            route(forHerobrine, "chat");
            return false;
        });
        ClientSendMessageEvents.MODIFY_CHAT.register(message -> Afk.MODE.isAgent() && message.startsWith("!") ? message.substring(1).strip() : message);
    }

    /** The text meant for Herobrine, or null if the message is for the server. */
    static String addressedText(String message) {
        String m = message.strip();
        if (m.isEmpty()) return null;
        if (Afk.MODE.isAgent()) return m.startsWith("!") ? null : m;
        Matcher matcher = ADDRESSED.matcher(m);
        if (!matcher.matches()) return null;
        String rest = matcher.group(2).strip();
        return rest.isEmpty() ? "hello" : rest;
    }

    void route(String text, String where) {
        lastHumanRequestMs = System.currentTimeMillis();
        Afk.EVENTS.add("chat", where.equals("chat") ? text : text + " (typed in " + where + ")");
        show(Component.literal("you → Herobrine  ").withColor(DIM).append(Component.literal(text).withColor(DIM)));
        if (System.currentTimeMillis() - BridgeServer.lastBrainContactMs() > 15_000) {
            showHerobrine("I'm not awake yet. Give me a few seconds" + (Afk.BRAIN.hasLaunched() ? "." : ", or sign in with /afk login."));
        }
    }

    void showHerobrine(String text) {
        String clean = text.replace('\n', ' ').strip();
        if (clean.length() > 400) clean = clean.substring(0, 400) + "...";
        MutableComponent line = Component.literal("Herobrine  ").withColor(EMBER).append(Component.literal(clean).withColor(INK));
        show(line);
    }

    static void show(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(c);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
