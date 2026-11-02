package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.ItemModelOptimization;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = {"addEntity", "removeEntity", "sendBlockUpdated", "onChunkLoaded", "unload"}, at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        ItemModelOptimization.RENDERER.sceneDirty = true;
    }
}
