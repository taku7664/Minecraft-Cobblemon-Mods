package jbro.cobblemon.popupemotes.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteCatalog;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteConfigStore;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteDefinition;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteDrafts;
import jbro.cobblemon.popupemotes.client.custom.RemoteEmoteManager;
import jbro.cobblemon.popupemotes.emote.RemoteEmoteUrlPolicy;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class CustomEmotesScreen extends Screen {
    private static final ResourceLocation EMPTY_TEXTURE = ResourceLocation.withDefaultNamespace("textures/item/barrier.png");
    private final Screen parent;
    private final CustomEmoteDrafts drafts;
    private List<CustomEmoteDrafts.Draft> savedSnapshot;
    private EditBox nameField;
    private EditBox urlField;
    private int page;
    private int selectedGlobalIndex = -1;
    private int draggedGlobalIndex = -1;
    private Component status = Component.empty();
    private int statusColor = 0xFFA0A0A0;

    public CustomEmotesScreen(Screen parent) {
        super(Component.translatable("screen.player_popup_emotes.my_emotes"));
        this.parent = parent;
        drafts = new CustomEmoteDrafts(CustomEmoteConfigStore.catalog().entries());
        savedSnapshot = drafts.snapshot();
        if (drafts.size() > 0) {
            selectedGlobalIndex = 0;
        }
        for (int index = 0; index < drafts.size(); index++) {
            if (RemoteEmoteUrlPolicy.isKnownWebPage(drafts.get(index).url())) {
                setError(Component.translatable("screen.player_popup_emotes.imgur_page_url"));
                break;
            }
        }
    }

    @Override
    protected void init() {
        buildScreenWidgets();
    }

    private void buildScreenWidgets() {
        clearWidgets();
        CustomEmotesLayout layout = layout();
        int panelLeft = layout.panelLeft();
        int panelWidth = layout.panelWidth();
        nameField = new EditBox(font, panelLeft, layout.nameFieldY(), panelWidth, 20, Component.translatable("screen.player_popup_emotes.name"));
        nameField.setMaxLength(CustomEmoteDefinition.MAX_NAME_LENGTH);
        urlField = new EditBox(font, panelLeft, layout.urlFieldY(), panelWidth, 20, Component.literal("URL"));
        urlField.setMaxLength(RemoteEmoteUrlPolicy.MAX_URL_LENGTH);
        boolean selected = hasSelection();
        nameField.active = selected;
        urlField.active = selected;
        if (selected) {
            var draft = drafts.get(selectedGlobalIndex);
            nameField.setValue(draft.name());
            urlField.setValue(draft.url());
        }
        nameField.setResponder(value -> updateSelectedDraft(value, urlField.getValue()));
        urlField.setResponder(value -> updateSelectedDraft(nameField.getValue(), value));
        addRenderableWidget(nameField);
        addRenderableWidget(urlField);

        int gap = 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.player_popup_emotes.add"), ignored -> addBlank())
            .bounds(layout.addButtonX(), layout.addButtonY(), layout.addButtonWidth(), 20).build());
        Button paste = Button.builder(Component.translatable("screen.player_popup_emotes.paste"), ignored -> pasteFromClipboard())
            .bounds(panelLeft, layout.primaryButtonsY(), panelWidth, 20).build();
        int orderWidth = (panelWidth - gap * 2) / 3;
        Button moveLeft = Button.builder(Component.translatable("screen.player_popup_emotes.move_left"), ignored -> moveSelected(-1))
            .bounds(panelLeft, layout.orderButtonsY(), orderWidth, 20).build();
        Button moveRight = Button.builder(Component.translatable("screen.player_popup_emotes.move_right"), ignored -> moveSelected(1))
            .bounds(panelLeft + orderWidth + gap, layout.orderButtonsY(), orderWidth, 20).build();
        Button delete = Button.builder(Component.translatable("screen.player_popup_emotes.delete"), ignored -> deleteSelected())
            .bounds(panelLeft + (orderWidth + gap) * 2, layout.orderButtonsY(), panelWidth - orderWidth * 2 - gap * 2, 20).build();
        paste.active = selected || drafts.size() < CustomEmoteCatalog.MAX_EMOTES;
        moveLeft.active = selected && selectedGlobalIndex > 0;
        moveRight.active = selected && selectedGlobalIndex < drafts.size() - 1;
        delete.active = selected;
        addRenderableWidget(paste);
        addRenderableWidget(moveLeft);
        addRenderableWidget(moveRight);
        addRenderableWidget(delete);

        if (drafts.pageCount() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(panelLeft, layout.pageButtonsY(), 28, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(layout.panelRight() - 28, layout.pageButtonsY(), 28, 20).build());
        }

        int bottomWidth = (panelWidth - gap) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.player_popup_emotes.save"), ignored -> save())
            .bounds(panelLeft, layout.bottomButtonsY(), bottomWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.player_popup_emotes.close"), ignored -> onClose())
            .bounds(panelLeft + bottomWidth + gap, layout.bottomButtonsY(), panelWidth - bottomWidth - gap, 20).build());
    }

    private void addBlank() {
        syncSelected();
        if (drafts.size() >= CustomEmoteCatalog.MAX_EMOTES) {
            setError(Component.translatable("screen.player_popup_emotes.limit", CustomEmoteCatalog.MAX_EMOTES));
            return;
        }
        selectedGlobalIndex = drafts.add(
            Component.translatable("screen.player_popup_emotes.default_name", drafts.size() + 1).getString(),
            ""
        );
        page = drafts.pageFor(selectedGlobalIndex);
        buildScreenWidgets();
        urlField.setFocused(true);
    }

    private void pasteFromClipboard() {
        syncSelected();
        String clipboard = minecraft.keyboardHandler.getClipboard().trim();
        if (RemoteEmoteUrlPolicy.isKnownWebPage(clipboard)) {
            setError(Component.translatable("screen.player_popup_emotes.imgur_page_url"));
            return;
        }
        if (!RemoteEmoteUrlPolicy.acceptsSyntax(clipboard)) {
            setError(Component.translatable("screen.player_popup_emotes.invalid_url"));
            return;
        }
        if (!hasSelection()) {
            if (drafts.size() >= CustomEmoteCatalog.MAX_EMOTES) {
                setError(Component.translatable("screen.player_popup_emotes.limit", CustomEmoteCatalog.MAX_EMOTES));
                return;
            }
            selectedGlobalIndex = drafts.add(
                Component.translatable("screen.player_popup_emotes.default_name", drafts.size() + 1).getString(),
                clipboard
            );
            page = drafts.pageFor(selectedGlobalIndex);
        } else {
            var draft = drafts.get(selectedGlobalIndex);
            drafts.set(selectedGlobalIndex, draft.name(), clipboard);
        }
        buildScreenWidgets();
    }

    private void moveSelected(int amount) {
        if (!hasSelection()) {
            return;
        }
        syncSelected();
        selectedGlobalIndex = drafts.move(selectedGlobalIndex, amount);
        page = drafts.pageFor(selectedGlobalIndex);
        buildScreenWidgets();
    }

    private void deleteSelected() {
        if (!hasSelection()) {
            return;
        }
        selectedGlobalIndex = drafts.remove(selectedGlobalIndex);
        if (selectedGlobalIndex >= 0) {
            page = drafts.pageFor(selectedGlobalIndex);
        } else {
            page = 0;
        }
        buildScreenWidgets();
    }

    private void changePage(int amount) {
        syncSelected();
        page = Math.floorMod(page + amount, drafts.pageCount());
        int first = page * CustomEmoteCatalog.PAGE_SIZE;
        selectedGlobalIndex = first < drafts.size() ? first : -1;
        buildScreenWidgets();
    }

    private void selectLocal(int localIndex) {
        int global = page * CustomEmoteCatalog.PAGE_SIZE + localIndex;
        if (global < 0 || global >= drafts.size() || global == selectedGlobalIndex) {
            return;
        }
        syncSelected();
        selectedGlobalIndex = global;
        buildScreenWidgets();
    }

    private void save() {
        syncSelected();
        try {
            for (int index = 0; index < drafts.size(); index++) {
                if (RemoteEmoteUrlPolicy.isKnownWebPage(drafts.get(index).url())) {
                    setError(Component.translatable("screen.player_popup_emotes.imgur_page_url"));
                    return;
                }
            }
            List<CustomEmoteDefinition> entries = drafts.definitions();
            CustomEmoteConfigStore.save(entries);
            for (CustomEmoteDefinition entry : entries) {
                RemoteEmoteManager.retry(entry.url());
            }
            savedSnapshot = drafts.snapshot();
            status = Component.translatable("screen.player_popup_emotes.saved", entries.size());
            statusColor = 0xFF70E090;
        } catch (IllegalArgumentException | IOException exception) {
            setError(Component.translatable("screen.player_popup_emotes.save_failed", exception.getMessage()));
        }
    }

    private void syncSelected() {
        if (hasSelection() && nameField != null && urlField != null) {
            drafts.set(selectedGlobalIndex, nameField.getValue().trim(), urlField.getValue().trim());
        }
    }

    private void updateSelectedDraft(String name, String url) {
        if (hasSelection()) {
            drafts.set(selectedGlobalIndex, name.trim(), url.trim());
        }
    }

    private boolean hasSelection() {
        return selectedGlobalIndex >= 0 && selectedGlobalIndex < drafts.size();
    }

    private CustomEmotesLayout layout() {
        return CustomEmotesLayout.forScreen(width, height);
    }

    private int wheelLocalAt(double mouseX, double mouseY) {
        CustomEmotesLayout layout = layout();
        return EmoteWheelSelection.index(
            (mouseX - layout.wheelCenterX()) / layout.wheelScale(),
            (mouseY - layout.wheelCenterY()) / layout.wheelScale(),
            wheelEntries().size()
        );
    }

    private List<WheelEmote> wheelEntries() {
        var result = new ArrayList<WheelEmote>();
        for (CustomEmoteDrafts.Draft draft : drafts.page(page)) {
            String label = draft.name().isBlank()
                ? Component.translatable("screen.player_popup_emotes.unnamed").getString()
                : draft.name();
            result.add(new WheelEmote(
                draft.url(),
                Component.literal(label),
                () -> textureFor(draft.url())
            ));
        }
        return List.copyOf(result);
    }

    private static ResourceLocation textureFor(String url) {
        if (!RemoteEmoteUrlPolicy.acceptsSyntax(url) || RemoteEmoteUrlPolicy.isKnownWebPage(url)) {
            return EMPTY_TEXTURE;
        }
        return RemoteEmoteManager.texture(url);
    }

    private int selectedLocalIndex() {
        if (!hasSelection() || drafts.pageFor(selectedGlobalIndex) != page) {
            return -1;
        }
        return selectedGlobalIndex % CustomEmoteCatalog.PAGE_SIZE;
    }

    private void setError(Component message) {
        status = message;
        statusColor = 0xFFFF7070;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            int local = wheelLocalAt(mouseX, mouseY);
            if (local >= 0) {
                draggedGlobalIndex = page * CustomEmoteCatalog.PAGE_SIZE + local;
                selectLocal(local);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggedGlobalIndex >= 0) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggedGlobalIndex >= 0) {
            int source = draggedGlobalIndex;
            draggedGlobalIndex = -1;
            int local = wheelLocalAt(mouseX, mouseY);
            if (local >= 0) {
                int target = page * CustomEmoteCatalog.PAGE_SIZE + local;
                if (target < drafts.size() && target != source) {
                    syncSelected();
                    drafts.swap(source, target);
                    selectedGlobalIndex = target;
                    page = drafts.pageFor(target);
                    buildScreenWidgets();
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0.0 && drafts.pageCount() > 1) {
            changePage(verticalAmount > 0.0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        CustomEmotesLayout layout = layout();
        graphics.fill(layout.panelLeft() - 4, 4, layout.panelRight() + 4, height - 4, 0xB810141C);
        super.render(graphics, mouseX, mouseY, partialTick);

        List<WheelEmote> emotes = wheelEntries();
        int hovered = wheelLocalAt(mouseX, mouseY);
        EmoteWheelRenderer.render(
            graphics,
            layout.wheelCenterX(),
            layout.wheelCenterY(),
            selectedLocalIndex(),
            hovered,
            emotes,
            layout.wheelScale()
        );

        int panelCenter = layout.panelLeft() + layout.panelWidth() / 2;
        graphics.drawCenteredString(font, title, panelCenter, 9, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.player_popup_emotes.name"), layout.panelLeft(), layout.nameFieldY() - 10, 0xFFD7DEEA);
        graphics.drawString(font, "URL", layout.panelLeft(), layout.urlFieldY() - 10, 0xFFD7DEEA);
        if (drafts.pageCount() > 1) {
            graphics.drawCenteredString(font, (page + 1) + " / " + drafts.pageCount(), panelCenter, layout.pageButtonsY() + 6, 0xFFFFFFFF);
        }
        if (!status.getString().isEmpty()) {
            graphics.fill(layout.panelLeft(), layout.statusTop(), layout.panelRight(), layout.statusBottom(), 0xF0202632);
            var lines = font.split(status, layout.panelWidth() - 8);
            int textY = layout.statusTop() + 3;
            for (int index = 0; index < Math.min(3, lines.size()); index++) {
                graphics.drawCenteredString(font, lines.get(index), panelCenter, textY + index * 9, statusColor);
            }
        }
        if (draggedGlobalIndex >= 0 && draggedGlobalIndex < drafts.size()) {
            CustomEmoteDrafts.Draft dragged = drafts.get(draggedGlobalIndex);
            int iconX = mouseX + 8;
            int iconY = mouseY + 8;
            graphics.pose().pushPose();
            graphics.pose().translate(iconX, iconY, 100.0F);
            graphics.pose().scale(1.25F, 1.25F, 1.0F);
            graphics.blit(textureFor(dragged.url()), 0, 0, 0.0F, 0.0F, 16, 16, 16, 16);
            graphics.pose().popPose();
        }
    }

    @Override
    public void onClose() {
        syncSelected();
        if (drafts.snapshot().equals(savedSnapshot)) {
            minecraft.setScreen(parent);
            return;
        }
        minecraft.setScreen(new ConfirmScreen(
            discard -> minecraft.setScreen(discard ? parent : this),
            Component.translatable("screen.player_popup_emotes.discard_title"),
            Component.translatable("screen.player_popup_emotes.discard_message"),
            Component.translatable("screen.player_popup_emotes.discard"),
            Component.translatable("screen.player_popup_emotes.keep_editing")
        ));
    }
}
