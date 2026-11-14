package dev.afk.mixin;

import dev.afk.AfkHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /** Right after vanilla places the camera, and before FOV and culling are computed from it. */
    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void afk$direct(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        double[] pose = AfkHooks.directCamera(self, self.getCameraEntityPartialTicks(deltaTracker));
        if (pose != null) {
            setPosition(pose[0], pose[1], pose[2]);
            setRotation((float) pose[3], (float) pose[4]);
            detached = pose[5] > 0.5; // false = first-person shot: the player's own body is not drawn
        }
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void afk$fov(float partial, CallbackInfoReturnable<Float> cir) {
        Float fov = AfkHooks.fovOverride();
        if (fov != null) cir.setReturnValue(fov);
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void afk$record(DeltaTracker deltaTracker, CallbackInfo ci) {
        AfkHooks.recordPose((Camera) (Object) this);
    }
}
