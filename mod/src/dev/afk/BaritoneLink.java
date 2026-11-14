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
 *  - legitMine is forced on, so `mine` only digs ores that have actually been seen;
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

    private IBaritone baritone;
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
        s.legitMine.value = true;
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
        pathing.estimatedTicksToGoal().ifPresent(t -> o.addProperty("etaSeconds", Math.round(t / 20.0)));
        o.addProperty("legitMine", true);
        return o;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
