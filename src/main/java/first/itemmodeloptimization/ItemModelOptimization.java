package first.itemmodeloptimization;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.itemmodeloptimization.client.ItemBatchRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(value = "item_model_optimization", dist = Dist.CLIENT)
public final class ItemModelOptimization {
    public static final ItemBatchRenderer RENDERER = new ItemBatchRenderer();
    public static final Settings CONFIG = new Settings();

    public ItemModelOptimization(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CONFIG.spec);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener((ResourceManagerReloadListener) manager -> RENDERER.invalidate()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> RENDERER.invalidate());
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> RENDERER.invalidate());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                RENDERER.flush();
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                RENDERER.flush();
                RENDERER.endFrame();
            }
        });
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                literal("imo")
                        .then(literal("enabled").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
                            CONFIG.enabled.set(BoolArgumentType.getBool(context, "enabled"));
                            CONFIG.spec.save();
                            return 1;
                        })))
                        .then(literal("stats").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(RENDERER.statistics()), false);
                            return 1;
                        }))));
    }

    public static final class Settings {
        public final ModConfigSpec spec;
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.BooleanValue computeAnimation;
        public final ModConfigSpec.BooleanValue skipVisibilityChecks;

        private Settings() {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
            enabled = builder.comment("Cache static ground item models and draw matching stacks in batches.").define("enabled", true);
            computeAnimation = builder.comment("Prepare entity animation once per instance using OpenGL 4.3; older devices retain vertex animation.").define("computeAnimation", true);
            skipVisibilityChecks = builder.comment("Submit batched items without vanilla frustum or Sodium visibility checks; distance limits still apply.").define("skipVisibilityChecks", true);
            spec = builder.build();
        }
    }
}
