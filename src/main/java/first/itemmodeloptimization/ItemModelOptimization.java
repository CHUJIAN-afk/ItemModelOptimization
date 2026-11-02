package first.itemmodeloptimization;

import first.itemmodeloptimization.client.ClientEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod("item_model_optimization")
public final class ItemModelOptimization {
    public static final Settings CONFIG = new Settings();

    public ItemModelOptimization() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientEvents::register);
    }

    public static final class Settings {
        public final ForgeConfigSpec spec;
        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.BooleanValue computeAnimation;
        public final ForgeConfigSpec.BooleanValue skipVisibilityChecks;

        private Settings() {
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            enabled = builder.comment("Cache static ground item models and draw matching stacks in batches.").define("enabled", true);
            computeAnimation = builder.comment("Prepare entity animation once per instance using OpenGL 4.3.").define("computeAnimation", true);
            skipVisibilityChecks = builder.comment("Submit batched items without vanilla frustum or Embeddium visibility checks; distance limits still apply.").define("skipVisibilityChecks", true);
            spec = builder.build();
        }
    }
}
