package dev.afk;

import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AFK: tap Tab and Herobrine (a Claude agent) takes over your body; tap again and it lets go.
 *
 * The mod is the agent's eyes and hands. A separate local "brain" process (Node + the Claude or
 * Codex CLI you are signed in to) decides what to do and talks to this mod over a loopback-only
 * HTTP API. Herobrine plays legitimately: it only learns what a player could see, and ores are
 * mined with Baritone's legit mode.
 */
public final class Afk implements ClientModInitializer {
    public static final String ID = "afk";
    /** Override with -Dafk.port=<n> (used by the offline test client so it can run beside a real game). */
    public static final int PORT = Integer.getInteger("afk.port", 25599);
    static final Logger LOG = LoggerFactory.getLogger("afk");

    static final EventLog EVENTS = new EventLog(300);
    static final AgentMode MODE = new AgentMode();
    static final ActivityLog ACTIVITY = new ActivityLog();
    static final Discoveries DISCOVERIES = new Discoveries();
    static final AfkHud HUD = new AfkHud();
    static final StateSampler STATE = new StateSampler();
    static final BaritoneLink BARITONE = new BaritoneLink();
    static final CinematicCamera CAMERA = new CinematicCamera();
    static final Hands HANDS = new Hands();
    static final ChatAgent CHAT = new ChatAgent();
    static final ContainerAssistant CONTAINERS = new ContainerAssistant();
    static final PerformanceGovernor PERFORMANCE = new PerformanceGovernor();
    static final BrainLauncher BRAIN = new BrainLauncher();

    /** gameDir/afk: Herobrine's memory, activity history and the brain live here. */
    static Path dataDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("afk");
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitializeClient() {
        MODE.registerKeys();
        HudElementRegistry.addLast(id("hud"), HUD);
        // Hold Tab still shows the player list; a quick tap toggles Herobrine without flashing it.
        HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST,
                original -> (graphics, delta) -> { if (!MODE.suppressPlayerList()) original.extractRenderState(graphics, delta); });
        // Chat stays readable: it moves up to sit above the bottom letterbox bar.
        HudElementRegistry.replaceElement(VanillaHudElements.CHAT, original -> (graphics, delta) -> {
            int lift = HUD.chatLift();
            if (lift <= 0) {
                original.extractRenderState(graphics, delta);
                return;
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate(0, -lift);
            original.extractRenderState(graphics, delta);
            graphics.pose().popMatrix();
        });
        // While the camera is cinematic, the vanilla HUD steps aside so the picture stays clean.
        for (Identifier element : List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
                VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
                VanillaHudElements.INFO_BAR_FOREGROUND, VanillaHudElements.CROSSHAIR, VanillaHudElements.HELD_ITEM_TOOLTIP,
                VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.MOB_EFFECTS)) {
            HudElementRegistry.replaceElement(element,
                    original -> (graphics, delta) -> { if (!CAMERA.hidesVanillaHud()) original.extractRenderState(graphics, delta); });
        }

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            MODE.tick(mc);
            BARITONE.tick(mc);
            STATE.tick(mc);
            HANDS.tick(mc);
            ACTIVITY.tick(mc);
            DISCOVERIES.tick(mc);
            CAMERA.tick(mc);
            PERFORMANCE.tick(mc);
            CONTAINERS.tick(mc);
            BRAIN.tick(mc);
        });
        ACTIVITY.registerEvents();
        DISCOVERIES.registerEvents();
        CHAT.register();
        CONTAINERS.register();
        Commands.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            PerformanceGovernor.recoverAfterCrash(mc); // settings left lowered by a crash or force quit go back first
            ACTIVITY.load();
            DISCOVERIES.load();
            VisualDefaults.applyOnce(mc);
            BRAIN.ensureConfig();
            com.google.gson.JsonObject cfg = BRAIN.config();
            if (cfg.has("moments") && !cfg.get("moments").getAsBoolean()) CAMERA.setMomentsEnabled(false);
        });
        BridgeServer.start(PORT);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            PERFORMANCE.restore(mc); // your own render and shader settings go back before the game saves them
            BridgeServer.stop();
            BRAIN.stop();
            ACTIVITY.save();
            DISCOVERIES.save();
        });
        LOG.info("AFK ready; Herobrine's bridge is on 127.0.0.1:{}", PORT);
    }
}
