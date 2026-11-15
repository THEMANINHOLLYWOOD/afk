package dev.afk;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The AFK panel (H): every switch in one small column. Herobrine on or off, Stop everything,
 * helpers, cinematic camera, shaders, the 24 fps ladder and discovery moments.
 */
final class ControlPanel extends Screen {
    private static final int W = 210;
    private static final int H = 20;
    private static final int GAP = 4;

    ControlPanel() {
        super(Component.literal("AFK"));
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        int x = (width - W) / 2;
        int rows = 9;
        int y = Math.max(8, (height - rows * (H + GAP)) / 2);

        addRenderableWidget(new StringWidget(x, y, W, 12, Component.literal("AFK  ·  HEROBRINE").withStyle(ChatFormatting.GRAY), font));
        y += 16;

        boolean agent = Afk.MODE.isAgent();
        row(x, y, (agent ? "Herobrine: playing for you" : "Herobrine: off") + "  ·  Tab", () -> {
            Afk.MODE.setAgent(!agent, "you used the AFK panel");
            onClose();
        });
        y += H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Stop everything  ·  Delete").withStyle(ChatFormatting.RED), b -> {
            Agents.stopEverything("you pressed Stop");
            onClose();
        }).bounds(x, y, W, H).build());
        y += H + GAP;

        int crew = Agents.count();
        Button call = Button.builder(Component.literal("Call a helper"), b -> {
            String msg = Agents.spawn(null, "the human");
            Afk.CHAT.showHerobrine(msg);
            onClose();
        }).bounds(x, y, (W - GAP) / 2, H).build();
        call.active = Agents.available() && crew < Agents.MAX;
        addRenderableWidget(call);
        Button dismiss = Button.builder(Component.literal(crew == 0 ? "No helpers" : "Dismiss " + crew), b -> {
            Afk.CHAT.showHerobrine(Agents.dismiss("all"));
            rebuild();
        }).bounds(x + (W + GAP) / 2, y, (W - GAP) / 2, H).build();
        dismiss.active = crew > 0;
        addRenderableWidget(dismiss);
        y += H + GAP + 6;

        row(x, y, "Cinematic camera: " + (Afk.CAMERA.isManual() ? "on" : "off") + "  ·  B", () -> {
            onClose();
            Afk.CAMERA.toggleManual(mc);
        });
        y += H + GAP;

        boolean shaders = ShaderProfiles.shadersOn();
        Button sh = Button.builder(Component.literal("Shaders: " + (shaders ? "on" : "off")), b -> {
            Afk.PERFORMANCE.userSetShaders(mc, !shaders);
            rebuild();
        }).bounds(x, y, W, H).build();
        sh.active = ShaderProfiles.irisPresent();
        addRenderableWidget(sh);
        y += H + GAP;

        row(x, y, "Keep 24 fps: " + (Afk.PERFORMANCE.enabled() ? "on" : "off"), () -> {
            Afk.PERFORMANCE.setEnabled(mc, !Afk.PERFORMANCE.enabled());
            rebuild();
        });
        y += H + GAP;

        row(x, y, "Discovery moments: " + (Afk.CAMERA.momentsEnabled() ? "on" : "off"), () -> {
            Commands.moments(!Afk.CAMERA.momentsEnabled());
            rebuild();
        });
        y += H + GAP + 6;

        row(x, y, "Done", this::onClose);
    }

    private void row(int x, int y, String label, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, W, H).build());
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
