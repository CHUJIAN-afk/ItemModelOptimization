package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.client.ClientEvents;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private void imo$entities(CallbackInfo ci) {
        ClientEvents.RENDERER.collecting = true;
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;", shift = At.Shift.AFTER))
    private void imo$collected(CallbackInfo ci) {
        ClientEvents.RENDERER.collecting = false;
    }

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void imo$frame(PoseStack pose, float partialTick, long finishTime, boolean outline, Camera camera, GameRenderer renderer, LightTexture light, Matrix4f projection, CallbackInfo ci) {
        ClientEvents.RENDERER.beginFrame(camera, pose.last().pose(), projection, partialTick);
    }
}
