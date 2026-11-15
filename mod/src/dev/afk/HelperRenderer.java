package dev.afk;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/** Draws a helper like a player wearing Herobrine's skin, with whatever he holds in his hand. */
final class HelperRenderer extends HumanoidMobRenderer<Helper, AvatarRenderState, PlayerModel> {
    private static final Identifier TEXTURE = Afk.id("textures/entity/herobrine.png");

    HelperRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return TEXTURE;
    }

    @Override
    public void extractRenderState(Helper helper, AvatarRenderState state, float partial) {
        super.extractRenderState(helper, state, partial);
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.skin = AfkHooks.herobrineSkin();
    }
}
