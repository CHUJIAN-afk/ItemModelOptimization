package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.client.ClientEvents;
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
        ClientEvents.RENDERER.sceneDirty = true;
    }
    @Inject(method = "entitiesForRendering", at = @At("RETURN"), cancellable = true)
    private void imo$collect(CallbackInfoReturnable<Iterable<Entity>> ci) {
        if (ClientEvents.RENDERER.collecting) {
            ClientEvents.RENDERER.collecting = false;
            ci.setReturnValue(ClientEvents.RENDERER.collect(ci.getReturnValue()));
        }
    }
}
