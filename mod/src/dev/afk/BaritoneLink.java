package dev.afk;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * Herobrine's legs. Only a small, legit set of Baritone commands is allowed:
 *  - for ores and anything else hidden, legitMine is forced on, so `mine` only digs what has actually been seen;
 *    trees, crops and common ground (stone, dirt, sand) are in plain sight and may be walked to directly;
 *  - `goto` takes coordinates only (never a block name, which would search hidden blocks);
 *  - nothing that scans the world for blocks (`find`, `goto <block>`, ...) is reachable.
 * Commands are refused unless Herobrine has control.
 */
final class BaritoneLink {
    private static final Set<String> ALLOWED_VERBS = Set.of(
            "mine", "goto", "stop", "cancel", "forcecancel", "explore", "sethome", "home", "surface",
            "tunnel", "thisway", "eta", "proc", "set", "farm");

    private static final Set<String> ALLOWED_SETTINGS = Set.of(
            "legitmineylevel", "allowsprint", "allowplace", "allowparkour", "allowdownward",
            "minylevelwhilemining", "maxylevelwhilemining", "minescandroppeditems",
            "avoidance", "mobavoidanceradius", "itemsaver", "itemsaverthreshold");

    private static final Pattern COORD = Pattern.compile("^~?-?\\d+$|^~$");
    private static final Pattern WORD = Pattern.compile("^[a-z0-9_:]+$");
    private static final long BRAIN_TIMEOUT_MS = 180_000;

    /** Things a player can simply see or knows are everywhere: Baritone may walk to these directly. */
    private static final Set<String> PLAIN_SIGHT = Set.of(
            "stone", "cobblestone", "deepslate", "cobbled_deepslate", "dirt", "grass_block", "sand", "red_sand", "gravel", "clay",
            "netherrack", "end_stone", "snow", "snow_block", "ice", "sandstone", "andesite", "diorite", "granite", "tuff", "calcite",
            "basalt", "blackstone", "terracotta", "moss_block", "wheat", "carrots", "potatoes", "beetroots", "sugar_cane", "bamboo",
            "cactus", "pumpkin", "melon", "kelp", "nether_wart", "cocoa", "sweet_berry_bush", "red_mushroom", "brown_mushroom");

    private IBaritone baritone;
    private volatile boolean legit = true;
    private boolean initFailed;
    private boolean pausedForHuman;
    private int tick;

    static String validate(String raw) {
        if (raw == null) throw new IllegalArgumentException("missing command");
        String cmd = raw.strip();
        while (cmd.startsWith("#")) cmd = cmd.substring(1).strip();
        if (cmd.isEmpty()) throw new IllegalArgumentException("empty command");
        if (cmd.length() > 200 || cmd.indexOf('\n') >= 0) throw new IllegalArgumentException("command too long or multi-line");
        String[] parts = cmd.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_VERBS.contains(verb)) throw new IllegalArgumentException("'" + verb + "' is not allowed; allowed: " + ALLOWED_VERBS);
        switch (verb) {
            case "goto" -> {
                if (parts.length < 2 || parts.length > 4) throw new IllegalArgumentException("goto takes 1 to 3 coordinates");
                for (int i = 1; i < parts.length; i++) {
                    if (!COORD.matcher(parts[i]).matches()) {
                        throw new IllegalArgumentException("goto only takes coordinates (going to a block type would search hidden blocks)");
                    }
                }
            }
            case "mine" -> {
                if (parts.length < 2) throw new IllegalArgumentException("mine needs at least one block name");
                for (int i = 1; i < parts.length; i++) {
                    if (!WORD.matcher(parts[i].toLowerCase(Locale.ROOT)).matches() && !parts[i].matches("\\d+")) {
                        throw new IllegalArgumentException("bad block name: " + parts[i]);
                    }
                }
            }
            case "set" -> {
                if (parts.length != 3 || !ALLOWED_SETTINGS.contains(parts[1].toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("only 'set <name> <value>' for " + ALLOWED_SETTINGS + " (legitMine is always on)");
                }
            }
            default -> { }
        }
        return cmd;
    }

    void tick(Minecraft mc) {
        if (baritone == null && !initFailed && mc.player != null) init();
        if (baritone == null) return;
        if (++tick % 20 == 0) enforceLegit();
        // Dead-man switch: if the brain disappears while Herobrine is driving, stop moving.
        if (Afk.MODE.isAgent() && isActive() && System.currentTimeMillis() - BridgeServer.lastBrainContactMs() > BRAIN_TIMEOUT_MS) {
            baritone.getPathingBehavior().cancelEverything();
            Afk.EVENTS.add("bridge", "the brain was silent for 3 minutes, so Baritone was stopped");
        }
    }

    private void enforceLegit() {
        Settings s = BaritoneAPI.getSettings();
        s.legitMine.value = legit;
        s.renderPath.value = false;
        s.renderGoal.value = false;
        s.renderSelectionBoxes.value = false;
        s.renderCachedChunks.value = false;
        s.antiCheatCompatibility.value = true;
        s.chatControl.value = false;
        s.prefixControl.value = false;
    }

    private void init() {
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            enforceLegit();
            // Baritone's chatter goes to Herobrine's event log instead of your chat.
            BaritoneAPI.getSettings().logger.value = message -> {
                try {
                    Afk.EVENTS.add("baritone", message.getString());
                } catch (Throwable ignored) {
                    // never let logging break Baritone
                }
            };
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    switch (event) {
                        case CALC_FAILED, NEXT_CALC_FAILED -> Afk.EVENTS.add("path", "path calculation failed");
                        case AT_GOAL -> Afk.EVENTS.add("path", "arrived at goal");
                        default -> { }
                    }
                }
            });
            Afk.LOG.info("Linked to Baritone");
        } catch (Throwable t) {
            initFailed = true;
            Afk.LOG.error("Could not link to Baritone", t);
            Afk.EVENTS.add("error", "Baritone is not available: " + t);
        }
    }

    boolean ready() {
        return baritone != null;
    }

    /** Runs an already-validated command for the brain. Client thread only. */
    boolean execute(String command) {
        if (baritone == null) throw new IllegalStateException("Baritone is not ready (join a world first)");
        if (!Afk.MODE.isAgent()) throw new IllegalStateException("the human has control right now");
        String[] parts = command.toLowerCase(Locale.ROOT).split("\\s+");
        if (parts[0].equals("mine")) {
            // Ores and anything else hidden: only what has been seen. Trees, crops and common ground: walk to them.
            boolean hidden = false;
            for (int i = 1; i < parts.length; i++) {
                String id = parts[i].replace("minecraft:", "");
                if (id.matches("\\d+")) continue;
                boolean plain = PLAIN_SIGHT.contains(id) || id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_leaves") || id.endsWith("_wood");
                if (!plain) hidden = true;
            }
            legit = hidden;
            enforceLegit();
        }
        return baritone.getCommandManager().execute(command);
    }

    void onAgentStart() {
        if (baritone == null) return;
        if (pausedForHuman) {
            baritone.getCommandManager().execute("resume");
            pausedForHuman = false;
        }
    }

    void onAgentStop() {
        if (baritone == null) return;
        if (isActive()) {
            baritone.getCommandManager().execute("pause");
            pausedForHuman = true;
        }
    }

    /** Hands need the body for a moment (crafting, eating): hold Baritone still. Returns true if it was paused. */
    boolean pauseForHands() {
        if (baritone == null || pausedForHuman || !isActive()) return false;
        baritone.getCommandManager().execute("pause");
        return true;
    }

    void resumeAfterHands() {
        if (baritone != null && !pausedForHuman && Afk.MODE.isAgent()) baritone.getCommandManager().execute("resume");
    }

    boolean isActive() {
        if (baritone == null) return false;
        Optional<IBaritoneProcess> proc = baritone.getPathingControlManager().mostRecentInControl();
        return proc.isPresent() && proc.get().isActive();
    }

    /** Remaining nodes of the path Baritone is walking, for the HUD. Client thread only. */
    List<int[]> upcomingPath(int max) {
        List<int[]> out = new ArrayList<>();
        if (baritone == null) return out;
        IPathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null) return out;
        IPath path = current.getPath();
        List<BetterBlockPos> positions = path.positions();
        for (int i = Math.max(0, current.getPosition()); i < positions.size() && out.size() < max; i++) {
            BetterBlockPos p = positions.get(i);
            out.add(new int[] {p.x, p.y, p.z});
        }
        IPathExecutor next = baritone.getPathingBehavior().getNext();
        if (next != null) {
            for (BetterBlockPos p : next.getPath().positions()) {
                if (out.size() >= max) break;
                out.add(new int[] {p.x, p.y, p.z});
            }
        }
        return out;
    }

    JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("ready", baritone != null);
        if (baritone == null) return o;
        Optional<IBaritoneProcess> proc = baritone.getPathingControlManager().mostRecentInControl();
        boolean active = proc.isPresent() && proc.get().isActive();
        o.addProperty("active", active);
        if (active) o.addProperty("process", clip(proc.get().displayName(), 140));
        IPathingBehavior pathing = baritone.getPathingBehavior();
        o.addProperty("pathing", pathing.isPathing());
        Goal goal = pathing.getGoal();
        if (goal != null) o.addProperty("goal", clip(goal.toString(), 120));
        pathing.estimatedTicksToGoal().ifPresent(t -> {
            if (t < 20 * 3600) o.addProperty("etaSeconds", Math.round(t / 20.0));
        });
        return o;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
