package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.ItemModelOptimization;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(method = {"addEntity", "removeEntity", "sendBlockUpdated", "onChunkLoaded", "unload"}, at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        ItemModelOptimization.RENDERER.sceneDirty = true;
    }
    @Inject(method = "entitiesForRendering", at = @At("RETURN"), cancellable = true)
    private void imo$collect(CallbackInfoReturnable<Iterable<Entity>> ci) {
        if (ItemModelOptimization.RENDERER.collecting) {
            ItemModelOptimization.RENDERER.collecting = false;
            ci.setReturnValue(ItemModelOptimization.RENDERER.collect(ci.getReturnValue()));
        }
    }
}
