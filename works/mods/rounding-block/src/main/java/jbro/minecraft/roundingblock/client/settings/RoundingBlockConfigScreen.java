package jbro.minecraft.roundingblock.client.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import jbro.minecraft.roundingblock.client.render.RoundedBlockModel;
import jbro.minecraft.roundingblock.client.render.fluid.RoundedWaterRenderHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native settings, accessible from the client command and optionally Mod Menu. */
public final class RoundingBlockConfigScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Rounding-Block");
    private static final String[][] FIELDS = {
        {"enabled", "radius", "segments"},
        {"fullBlockPlans", "slabPlans", "complexShapePlans", "fluidContactPlans", "weightedModelVariants"},
        {"diagnosticLogging"}
    };
    private static final String[] CATEGORIES = {"appearance", "cache", "debug"};
    private final Screen parent;
    private final RoundingBlockConfigDraft draft;
    private int category;
    private boolean applying;
    private Component status = Component.empty();
    private Button saveButton;

    public RoundingBlockConfigScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
        draft = new RoundingBlockConfigDraft(RoundedBlockModel.currentConfig());
    }

    @Override
    protected void init() {
        int left = (width - 310) / 2;
        int top = height / 2 - 92;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int selected = i;
            Button tab = addRenderableWidget(Button.builder(text("category." + CATEGORIES[i]), ignored -> {
                category = selected;
                rebuildWidgets();
            }).bounds(left + i * 105, top, 100, 20).build());
            tab.active = !applying && category != i;
        }
        for (int i = 0; i < FIELDS[category].length; i++) {
            String key = FIELDS[category][i];
            int y = top + 30 + i * 24;
            if (key.equals("enabled") || key.equals("diagnosticLogging")) {
                Button toggle = addRenderableWidget(Button.builder(booleanText(key), button -> {
                    draft.set(key, Boolean.toString(!Boolean.parseBoolean(draft.get(key))));
                    button.setMessage(booleanText(key));
                }).bounds(left + 200, y, 110, 20).build());
                toggle.setTooltip(Tooltip.create(text(key + ".tooltip")));
                toggle.active = !applying;
            } else {
                EditBox field = new EditBox(font, left + 200, y, 110, 20, text(key));
                field.setMaxLength(24);
                field.setValue(draft.get(key));
                field.setTooltip(Tooltip.create(text(key + ".tooltip")));
                field.setResponder(value -> {
                    draft.set(key, value);
                    validate();
                });
                field.setEditable(!applying);
                addRenderableWidget(field);
            }
        }
        Button defaults = addRenderableWidget(Button.builder(text("defaults"), ignored -> {
            draft.reset(RoundingBlockConfig.defaults());
            rebuildWidgets();
        }).bounds(left, top + 174, 100, 20).build());
        defaults.active = !applying;
        saveButton = addRenderableWidget(Button.builder(text("save"), ignored -> saveAndApply())
            .bounds(left + 105, top + 174, 100, 20).build());
        Button cancel = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), ignored -> onClose())
            .bounds(left + 210, top + 174, 100, 20).build());
        cancel.active = !applying;
        validate();
    }

    private Component booleanText(String key) {
        return Component.translatable(Boolean.parseBoolean(draft.get(key)) ? "options.on" : "options.off");
    }

    private void validate() {
        if (saveButton == null) return;
        try {
            draft.build();
            saveButton.active = !applying;
            if (!applying) status = Component.empty();
        } catch (IllegalArgumentException failure) {
            saveButton.active = false;
            status = text("invalid");
        }
    }

    private void saveAndApply() {
        final RoundingBlockConfig updated;
        try {
            updated = draft.build();
        } catch (IllegalArgumentException failure) {
            validate();
            return;
        }
        RoundingBlockConfig previous = RoundedBlockModel.currentConfig();
        if (updated.equals(previous)) {
            onClose();
            return;
        }
        Path path = FabricLoader.getInstance().getConfigDir().resolve("rounding-block.json");
        try {
            updated.save(path);
        } catch (IOException failure) {
            LOGGER.error("Could not save Rounding-Block settings", failure);
            status = text("saveFailed");
            return;
        }
        RoundedBlockModel.PreparedRuntime previousRuntime = RoundedBlockModel.currentRuntimeSnapshot();
        applying = true;
        rebuildWidgets();
        status = text("applying");
        CompletableFuture.supplyAsync(() -> RoundedBlockModel.prepareConfig(updated))
            .whenComplete((prepared, failure) -> minecraft.execute(() -> {
                if (failure != null) {
                    rollback(path, previous, previousRuntime, failure);
                    return;
                }
                try {
                    RoundedBlockModel.activatePreparedRuntime(prepared);
                    RoundedWaterRenderHandler.setEnabled(updated.enabled());
                    minecraft.reloadResourcePacks().whenComplete((ignored, reloadFailure) -> minecraft.execute(() -> {
                        if (reloadFailure != null) rollback(path, previous, previousRuntime, reloadFailure);
                        else {
                            applying = false;
                            onClose();
                        }
                    }));
                } catch (RuntimeException applyFailure) {
                    rollback(path, previous, previousRuntime, applyFailure);
                }
            }));
    }

    private void rollback(Path path, RoundingBlockConfig previous, RoundedBlockModel.PreparedRuntime previousRuntime, Throwable failure) {
        RoundedBlockModel.activatePreparedRuntime(previousRuntime);
        RoundedWaterRenderHandler.setEnabled(previous.enabled());
        boolean fileRestored = true;
        try {
            previous.save(path);
        } catch (IOException rollbackFailure) {
            fileRestored = false;
            failure.addSuppressed(rollbackFailure);
        }
        LOGGER.error("Could not apply Rounding-Block settings; restored previous settings",
            failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
        applying = false;
        rebuildWidgets();
        status = text(fileRestored ? "applyFailed" : "rollbackFailed");
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !applying;
    }

    @Override
    public void onClose() {
        if (!applying) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (width - 310) / 2;
        int top = height / 2 - 92;
        graphics.drawCenteredString(font, title, width / 2, top - 20, 0xFFFFFFFF);
        for (int i = 0; i < FIELDS[category].length; i++) {
            graphics.drawString(font, text(FIELDS[category][i]), left, top + 36 + i * 24, 0xFFFFFFFF);
        }
        graphics.drawCenteredString(font, status, width / 2, top + 154, 0xFFFFAA70);
    }

    private static Component text(String key) {
        return Component.translatable("rounding_block.config." + key);
    }
}
