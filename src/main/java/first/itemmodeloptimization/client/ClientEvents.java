package first.itemmodeloptimization.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.itemmodeloptimization.ItemModelOptimization;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class ClientEvents {
    public static final ItemBatchRenderer RENDERER = new ItemBatchRenderer();

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ItemModelOptimization.CONFIG.spec);
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new ItemConfigScreen(parent)));
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> RENDERER.invalidate()));
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> RENDERER.invalidate());
        MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> RENDERER.invalidate());
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                RENDERER.flush();
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                RENDERER.flush();
                RENDERER.endFrame();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                literal("imo")
                        .then(literal("enabled").then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
                            ItemModelOptimization.CONFIG.enabled.set(BoolArgumentType.getBool(context, "enabled"));
                            ItemModelOptimization.CONFIG.spec.save();
                            return 1;
                        })))
                        .then(literal("stats").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(RENDERER.statistics()), false);
                            return 1;
                        }))));
    }
}
