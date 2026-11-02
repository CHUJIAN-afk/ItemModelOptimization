package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import first.itemmodeloptimization.client.ClientEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntitySceneMixin {
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void imo$position(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && RenderSystem.isOnRenderThread()
                && (item.getX() != x || item.getY() != y || item.getZ() != z)) {
            ClientEvents.RENDERER.sceneDirty = true;
        }
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity && RenderSystem.isOnRenderThread()) {
            ClientEvents.RENDERER.sceneDirty = true;
        }
    }
}
