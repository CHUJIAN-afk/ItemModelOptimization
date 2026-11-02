package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.client.ClientEvents;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void imo$light(CallbackInfo ci) {
        ClientEvents.RENDERER.sceneDirty = true;
    }
}
