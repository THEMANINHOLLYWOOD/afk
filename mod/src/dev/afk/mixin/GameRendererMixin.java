package dev.afk.mixin;

import dev.afk.AfkHooks;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** No block-selection outline while the cinematic camera is filming. */
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void afk$noOutline(CallbackInfoReturnable<Boolean> cir) {
        if (AfkHooks.hideBlockOutline()) cir.setReturnValue(false);
    }
}
