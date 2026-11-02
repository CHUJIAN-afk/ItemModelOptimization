package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.itemmodeloptimization.ItemModelOptimization;
import first.itemmodeloptimization.client.ItemBatchRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin implements ItemBatchRenderer.ShadowSettings {
    @Shadow
    private boolean renderHitBoxes;
    @Shadow
    private boolean shouldRenderShadow;

    @Override
    public boolean imo$shadows() {
        return shouldRenderShadow;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void imo$direct(Entity entity, double x, double y, double z, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (entity instanceof ItemEntity item && !renderHitBoxes && !item.hasCustomName() && !item.shouldShowName() && !item.displayFireAnimation() && !item.isCurrentlyGlowing() && ((EntityRenderDispatcher) (Object) this).getRenderer(item).getClass() == ItemEntityRenderer.class) {
            var renderer = ItemModelOptimization.RENDERER;
            if (renderer.submitEntity(item, partialTick, light)) {
                renderer.addShadow(item, partialTick, shouldRenderShadow && Minecraft.getInstance().options.entityShadows().get());
                ci.cancel();
            }
        }
    }
}
