package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.ItemModelOptimization;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> imo$entities(ClientLevel level, Operation<Iterable<Entity>> original) {
        var renderer = ItemModelOptimization.RENDERER;
        renderer.collecting = true;
        try {
            return original.call(level);
        } finally {
            renderer.collecting = false;
        }
    }

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void imo$frame(DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        ItemModelOptimization.RENDERER.beginFrame(camera, view, projection, delta.getGameTimeDeltaPartialTick(false));
    }
}
