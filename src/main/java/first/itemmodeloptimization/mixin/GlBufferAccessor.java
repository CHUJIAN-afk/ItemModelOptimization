package first.itemmodeloptimization.mixin;

import com.mojang.blaze3d.opengl.GlBuffer;
import first.itemmodeloptimization.client.ItemBatchRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface GlBufferAccessor extends ItemBatchRenderer.BufferAccess {
    @Override
    @Accessor("handle")
    int imo$handle();
}
