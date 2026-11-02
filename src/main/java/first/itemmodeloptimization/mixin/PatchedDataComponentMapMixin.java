package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.client.ItemBatchRenderer;
import first.itemmodeloptimization.ItemModelOptimization;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PatchedDataComponentMap.class)
public abstract class PatchedDataComponentMapMixin implements ItemBatchRenderer.ComponentVersion {
    @Unique
    private int imo$version;
    @Unique
    private boolean imo$tracked;

    @Override
    public void imo$track() {
        imo$tracked = true;
    }

    @Override
    public boolean imo$tracked() {
        return imo$tracked;
    }

    @Override
    public int imo$version() {
        return imo$version;
    }

    @Inject(method = "ensureMapOwnership", at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        imo$version++;
        if (imo$tracked && RenderSystem.isOnRenderThread()) ItemModelOptimization.RENDERER.sceneDirty = true;
    }
}
