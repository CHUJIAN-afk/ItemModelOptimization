package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.itemmodeloptimization.client.ClientEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity> {
    @Shadow(remap = false)
    public abstract boolean shouldBob();

    @Shadow(remap = false)
    public abstract boolean shouldSpreadItems();

    @Shadow
    protected abstract int getRenderAmount(ItemStack stack);

    protected ItemEntityRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void imo$entity(ItemEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (ClientEvents.RENDERER.submitEntity(entity, partialTick, light, shouldBob(), shouldSpreadItems(), getRenderAmount(entity.getItem()))) {
            super.render(entity, yaw, partialTick, pose, buffers, light);
            ci.cancel();
        }
    }
}
