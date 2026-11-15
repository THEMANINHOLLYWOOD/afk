package dev.afk.mixin;

import dev.afk.AfkHooks;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    /** Herobrine's skin while he has your body, and on his helpers. Only you see it. */
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void afk$herobrineSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = AfkHooks.skinFor((AbstractClientPlayer) (Object) this);
        if (skin != null) cir.setReturnValue(skin);
    }
}
