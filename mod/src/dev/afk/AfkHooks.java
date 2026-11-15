package dev.afk;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** The few entry points the mixins call into. Public so classes in dev.afk.mixin can reach them. */
public final class AfkHooks {
    private AfkHooks() {
    }

    /** Pose for this frame as {x, y, z, yRot, xRot, detached(1/0)}, or null to leave the vanilla camera alone. */
    public static double[] directCamera(Camera camera, float partial) {
        try {
            CinematicCamera.Pose p = Afk.CAMERA.direct(camera, partial);
            return p == null ? null : new double[] {p.x(), p.y(), p.z(), p.yRot(), p.xRot(), p.detached() ? 1 : 0};
        } catch (Throwable t) {
            Afk.LOG.error("Cinematic camera failed; leaving the normal camera", t);
            return null;
        }
    }

    public static Float fovOverride() {
        return Afk.CAMERA.fovOverride();
    }

    /** The outline around the block under the crosshair has no place in a film shot. */
    public static boolean hideBlockOutline() {
        return Afk.CAMERA.hidesBlockOutline();
    }

    private static PlayerSkin herobrine;

    /** The retired-Herobrine skin, while he drives your body. Null means "your own skin". */
    public static PlayerSkin skinFor(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        return player == mc.player && Afk.MODE.isAgent() ? herobrineSkin() : null;
    }

    static PlayerSkin herobrineSkin() {
        if (herobrine == null) {
            herobrine = new PlayerSkin(new ClientAsset.ResourceTexture(Afk.id("entity/herobrine")), null, null, PlayerModelType.WIDE, true);
        }
        return herobrine;
    }

    /** Frames rendered so far (the camera updates once per frame). */
    static volatile long frames;

    public static void recordPose(Camera camera) {
        frames++;
        CinematicCamera.recordPose(camera);
    }

    /** While Herobrine drives, your mouse doesn't turn the body. */
    public static boolean lockMouse() {
        return Afk.MODE.isAgent() && Minecraft.getInstance().gui.screen() == null;
    }
}
