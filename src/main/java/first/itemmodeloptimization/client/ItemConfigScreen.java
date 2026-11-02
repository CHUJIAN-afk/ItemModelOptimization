package first.itemmodeloptimization.client;

import first.itemmodeloptimization.ItemModelOptimization;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class ItemConfigScreen extends Screen {
    private final Screen parent;

    public ItemConfigScreen(Screen parent) {
        super(Component.translatable("item_model_optimization.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var settings = java.util.List.of(ItemModelOptimization.CONFIG.enabled,
                ItemModelOptimization.CONFIG.computeAnimation, ItemModelOptimization.CONFIG.skipVisibilityChecks);
        String[] names = {"enabled", "compute", "visibility"};
        for (int i = 0; i < settings.size(); i++) {
            var setting = settings.get(i);
            addRenderableWidget(CycleButton.onOffBuilder(setting.get())
                    .create(width / 2 - 100, height / 2 - 34 + i * 24, 200, 20,
                            Component.translatable("item_model_optimization.config." + names[i]), (button, value) -> {
                                setting.set(value);
                                ItemModelOptimization.CONFIG.spec.save();
                                ClientEvents.RENDERER.invalidate();
                            }));
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 40, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 30, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
