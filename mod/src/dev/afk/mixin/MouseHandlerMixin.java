package dev.afk.mixin;

import dev.afk.AfkHooks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    /** Herobrine has the body: mouse movement is dropped instead of turning the player. */
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void afk$lock(CallbackInfo ci) {
        if (AfkHooks.lockMouse()) {
            accumulatedDX = 0;
            accumulatedDY = 0;
        }
    }
}
