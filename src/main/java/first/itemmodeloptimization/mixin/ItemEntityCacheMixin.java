package first.itemmodeloptimization.mixin;

import first.itemmodeloptimization.client.ItemBatchRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ItemEntity.class)
public abstract class ItemEntityCacheMixin implements ItemBatchRenderer.EntityCacheAccess {
    @Unique
    private final ItemBatchRenderer.EntityCache imo$cache = new ItemBatchRenderer.EntityCache();

    @Override
    public ItemBatchRenderer.EntityCache imo$cache() {
        return imo$cache;
    }
}
