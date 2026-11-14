package dev.afk;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

/** The few entry points the mixins call into. Public so classes in dev.afk.mixin can reach them. */
public final class AfkHooks {
    private AfkHooks() {
    }

    /** Pose for this frame as {x, y, z, yRot, xRot}, or null to leave the vanilla camera alone. */
    public static double[] directCamera(Camera camera, float partial) {
        try {
            CinematicCamera.Pose p = Afk.CAMERA.direct(camera, partial);
            return p == null ? null : new double[] {p.x(), p.y(), p.z(), p.yRot(), p.xRot()};
        } catch (Throwable t) {
            Afk.LOG.error("Cinematic camera failed; leaving the normal camera", t);
            return null;
        }
    }

    public static Float fovOverride() {
        return Afk.CAMERA.fovOverride();
    }

    public static void recordPose(Camera camera) {
        CinematicCamera.recordPose(camera);
    }

    /** While Herobrine drives, your mouse doesn't turn the body. */
    public static boolean lockMouse() {
        return Afk.MODE.isAgent() && Minecraft.getInstance().gui.screen() == null;
    }
}
