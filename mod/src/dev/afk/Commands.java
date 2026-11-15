package dev.afk;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

/**
 * Client-only commands (never sent to the server):
 *   /do <task>      give Herobrine a task in plain words; he takes control
 *   /do             show the current task          /do stop   take control back
 *   /afk            help    /afk status    /afk login [claude|codex]    /afk provider <claude|codex>
 *   /afk perf <on|off>     /afk moments <on|off>     /afk cinematic     /afk brain restart
 *   /afk panel   /afk stop   /afk shaders <on|off>
 *   /agent [task]   /agent tell <name> <task>   /agent list | stop | dismiss [name|all]
 */
final class Commands {
    private Commands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerAll(dispatcher));
    }

    private static void registerAll(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommands.literal("do")
                .executes(c -> {
                    String task = Afk.MODE.task();
                    Afk.CHAT.showHerobrine(task == null ? "No task. Try /do mine some iron, or just tap Tab." : "Working on: " + task);
                    return 1;
                })
                .then(ClientCommands.argument("task", StringArgumentType.greedyString()).executes(c -> {
                    String task = StringArgumentType.getString(c, "task").strip();
                    if (task.equalsIgnoreCase("stop") || task.equalsIgnoreCase("cancel")) {
                        Afk.MODE.clearTask();
                        Afk.MODE.setAgent(false, "you typed /do stop");
                        return 1;
                    }
                    Afk.MODE.setTask(task, true);
                    Afk.CHAT.route(task, "/do");
                    return 1;
                })));

        d.register(ClientCommands.literal("agent")
                .executes(c -> {
                    Afk.CHAT.showHerobrine(Agents.spawn(null, "the human"));
                    return 1;
                })
                .then(ClientCommands.argument("what", StringArgumentType.greedyString()).executes(c -> {
                    agent(StringArgumentType.getString(c, "what").strip());
                    return 1;
                })));

        d.register(ClientCommands.literal("afk")
                .executes(c -> {
                    help();
                    return 1;
                })
                .then(ClientCommands.literal("panel").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.gui.setScreen(new ControlPanel()));
                    return 1;
                }))
                .then(ClientCommands.literal("stop").executes(c -> {
                    Agents.stopEverything("you typed /afk stop");
                    return 1;
                }))
                .then(ClientCommands.literal("shaders")
                        .then(ClientCommands.literal("on").executes(c -> shaders(true)))
                        .then(ClientCommands.literal("off").executes(c -> shaders(false))))
                .then(ClientCommands.literal("status").executes(c -> {
                    JsonObject cfg = Afk.BRAIN.config();
                    boolean awake = System.currentTimeMillis() - BridgeServer.lastBrainContactMs() < 15_000;
                    Afk.CHAT.showHerobrine((Afk.MODE.isAgent() ? "I have control. " : "You have control. ")
                            + "Brain: " + (awake ? "awake" : "not connected") + " · sign-in: " + cfg.get("provider").getAsString()
                            + " · performance: " + Afk.PERFORMANCE.describe());
                    return 1;
                }))
                .then(ClientCommands.literal("login")
                        .executes(c -> {
                            Afk.CHAT.showHerobrine(BrainLauncher.login(Afk.BRAIN.config().get("provider").getAsString()));
                            return 1;
                        })
                        .then(ClientCommands.literal("claude").executes(c -> login("claude")))
                        .then(ClientCommands.literal("codex").executes(c -> login("codex"))))
                .then(ClientCommands.literal("provider")
                        .then(ClientCommands.literal("claude").executes(c -> provider("claude")))
                        .then(ClientCommands.literal("codex").executes(c -> provider("codex"))))
                .then(ClientCommands.literal("perf")
                        .then(ClientCommands.literal("on").executes(c -> perf(true)))
                        .then(ClientCommands.literal("off").executes(c -> perf(false))))
                .then(ClientCommands.literal("moments")
                        .then(ClientCommands.literal("on").executes(c -> moments(true)))
                        .then(ClientCommands.literal("off").executes(c -> moments(false))))
                .then(ClientCommands.literal("cinematic").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> Afk.CAMERA.toggleManual(mc));
                    return 1;
                }))
                .then(ClientCommands.literal("brain").then(ClientCommands.literal("restart").executes(c -> {
                    Afk.BRAIN.restart();
                    Afk.CHAT.showHerobrine("Restarting my brain...");
                    return 1;
                }))));
    }

    /**
     * /agent                       call a helper (he follows you)
     * /agent <task>                call a helper for a task
     * /agent tell <name> <task>    give a helper (or "all") something to do
     * /agent list | stop | dismiss [name|all]
     */
    private static void agent(String what) {
        String lower = what.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("stop") || lower.equals("cancel") || lower.equals("stop all")) {
            Agents.stopEverything("you typed /agent stop");
            Afk.CHAT.showHerobrine("Everyone stopped. Helpers are waiting for you.");
        } else if (lower.equals("list") || lower.equals("status")) {
            Afk.CHAT.showHerobrine(Agents.list());
        } else if (lower.equals("dismiss") || lower.startsWith("dismiss ")) {
            String who = what.length() > 7 ? what.substring(7).strip() : "all";
            Afk.CHAT.showHerobrine(Agents.dismiss(who.isEmpty() ? "all" : who));
        } else if (lower.startsWith("tell ")) {
            String rest = what.substring(5).strip();
            int space = rest.indexOf(' ');
            if (space < 0) {
                Afk.CHAT.showHerobrine("Tell who what? For example: /agent tell II chop 20 logs");
                return;
            }
            Afk.CHAT.showHerobrine(Agents.tell(rest.substring(0, space), rest.substring(space + 1).strip(), null));
        } else {
            Afk.CHAT.showHerobrine(Agents.spawn(what, "the human"));
        }
    }

    private static int shaders(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (!ShaderProfiles.irisPresent()) {
            Afk.CHAT.showHerobrine("Shaders need Iris, which isn't installed.");
            return 1;
        }
        mc.execute(() -> Afk.PERFORMANCE.userSetShaders(mc, on));
        return 1;
    }

    private static int login(String provider) {
        provider(provider);
        Afk.CHAT.showHerobrine(BrainLauncher.login(provider));
        return 1;
    }

    private static int provider(String provider) {
        JsonObject cfg = Afk.BRAIN.config();
        cfg.addProperty("provider", provider);
        Afk.BRAIN.saveConfig(cfg);
        Afk.BRAIN.restart();
        Afk.CHAT.showHerobrine("Using " + (provider.equals("codex") ? "Codex" : "Claude") + " from now on.");
        return 1;
    }

    static int moments(boolean on) {
        JsonObject cfg = Afk.BRAIN.config();
        cfg.addProperty("moments", on);
        Afk.BRAIN.saveConfig(cfg);
        Afk.CAMERA.setMomentsEnabled(on);
        Afk.CHAT.showHerobrine(on ? "Discovery moments on: title cards, and a short cinematic for the big ones." : "Discovery moments off.");
        return 1;
    }

    private static int perf(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        Afk.PERFORMANCE.setEnabled(mc, on);
        Afk.CHAT.showHerobrine("Performance governor " + (on ? "on: I'll keep you at 24 fps or better." : "off: your settings stay as they are."));
        return 1;
    }

    private static void help() {
        Afk.CHAT.showHerobrine("Tap Tab: I take over, tap again: you're back. H: the AFK panel. Delete: stop everything. B: cinematic camera. "
                + "Talk to me: \"hb <anything>\" in chat (while I have control, just type; start with ! to talk to the server). /do <task>. "
                + "/agent <task>: call a helper with his own body (singleplayer). In a crafting table or chest, use the box at the bottom. "
                + "/afk login, /afk status, /afk shaders on|off, /afk perf on|off, /afk moments on|off.");
    }
}
