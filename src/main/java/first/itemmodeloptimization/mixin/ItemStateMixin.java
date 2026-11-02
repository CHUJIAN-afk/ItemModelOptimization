package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import first.itemmodeloptimization.ItemModelOptimization;
import first.itemmodeloptimization.client.ItemBatchRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.List;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStateMixin implements ItemBatchRenderer.ItemStateAccess {
    @Shadow private ItemDisplayContext displayContext;
    @Unique private ItemStack imo$stack = ItemStack.EMPTY;
    @Unique private final List<Object> imo$identity = new ArrayList<>();
    @Override public ItemStack imo$stack() { return imo$stack; }
    @Override public void imo$stack(ItemStack stack) { imo$stack = stack; }
    @Override public List<Object> imo$identity() { return imo$identity; }

    @Inject(method = "clear", at = @At("HEAD"))
    private void imo$clear(CallbackInfo ci) {
        imo$stack = ItemStack.EMPTY;
        imo$identity.clear();
    }

    @Inject(method = "appendModelIdentityElement", at = @At("HEAD"))
    private void imo$identity(Object element, CallbackInfo ci) {
        imo$identity.add(element);
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void imo$submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline, CallbackInfo ci) {
        if (displayContext == ItemDisplayContext.GROUND && ItemModelOptimization.RENDERER.submit((ItemStackRenderState) (Object) this, pose, light, overlay, outline)) ci.cancel();
    }
}
