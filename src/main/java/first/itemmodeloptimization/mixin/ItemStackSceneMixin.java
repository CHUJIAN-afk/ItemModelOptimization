package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import first.itemmodeloptimization.client.ClientEvents;
import first.itemmodeloptimization.client.ItemBatchRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class ItemStackSceneMixin implements ItemBatchRenderer.TrackedStack {
    @Unique
    private boolean imo$tracked;

    @Override
    public void imo$track() {
        imo$tracked = true;
    }

    @Inject(method = {"setCount", "setTag"}, at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        if (imo$tracked && RenderSystem.isOnRenderThread()) ClientEvents.RENDERER.sceneDirty = true;
    }
}
