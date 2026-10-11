package kr.parkjh.pokefusion.client

import kr.parkjh.pokefusion.FusionMaterialLogic
import kr.parkjh.pokefusion.PokeFusionLayout
import kr.parkjh.pokefusion.PokeFusionMenu
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

/**
 * 합성 전용 화면. 칸 테두리와 배경은 바닐라 상자·화로 텍스처 조각으로 그려서 리소스팩 테마를 그대로 따른다.
 */
class PokeFusionScreen(menu: PokeFusionMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<PokeFusionMenu>(menu, inventory, title) {
    private lateinit var confirmButton: Button

    init {
        imageWidth = PokeFusionLayout.IMAGE_WIDTH
        imageHeight = PokeFusionLayout.IMAGE_HEIGHT
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()
        confirmButton = addRenderableWidget(
            Button.builder(Component.translatable("item.pokefusion.cannot_fuse")) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, PokeFusionMenu.CONFIRM_BUTTON)
            }
                .bounds(
                    leftPos + PokeFusionLayout.BUTTON_X,
                    topPos + PokeFusionLayout.BUTTON_Y,
                    PokeFusionLayout.BUTTON_WIDTH,
                    PokeFusionLayout.BUTTON_HEIGHT
                )
                .build()
        )
        updateButton()
    }

    override fun containerTick() {
        super.containerTick()
        updateButton()
    }

    private fun updateButton() {
        val (key, active) = when (menu.buttonState) {
            PokeFusionMenu.BUTTON_CONFIRM -> "item.pokefusion.confirm" to true
            PokeFusionMenu.BUTTON_RETRY -> "item.pokefusion.retry_results" to true
            PokeFusionMenu.BUTTON_STORAGE_ERROR -> "item.pokefusion.storage_error" to false
            else -> "item.pokefusion.cannot_fuse" to false
        }
        confirmButton.message = Component.translatable(key)
        confirmButton.active = active
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos
        val areaBottom = PokeFusionLayout.FUSION_AREA_BOTTOM

        // 상자 텍스처의 제목줄을 위에 두고, 칸이 없는 제목줄 띠를 이어 붙여 합성 영역 배경을 만든다.
        graphics.blit(CHEST, x, y, 0, 0, imageWidth, HEADER_HEIGHT)
        var bandY = HEADER_HEIGHT
        while (bandY < areaBottom) {
            val height = minOf(BAND_HEIGHT, areaBottom - bandY)
            graphics.blit(CHEST, x, y + bandY, 0, BAND_V, imageWidth, height)
            bandY += height
        }
        graphics.blit(CHEST, x, y + areaBottom, 0, INVENTORY_V, imageWidth, INVENTORY_HEIGHT)

        drawSlot(graphics, PokeFusionLayout.BASE_X, PokeFusionLayout.BASE_Y)
        repeat(FusionMaterialLogic.MAX_MATERIALS) { index ->
            drawSlot(graphics, PokeFusionLayout.materialX(index), PokeFusionLayout.materialY(index))
        }
        drawLargeSlot(graphics, PokeFusionLayout.PREVIEW_X, PokeFusionLayout.PREVIEW_Y)
        graphics.blit(FURNACE, x + PokeFusionLayout.ARROW_X, y + PokeFusionLayout.ARROW_Y, 79, 34, 24, 17)

        // 빈 칸은 역할별 색으로 옅게 칠해 장비칸처럼 자리를 구분한다.
        tintIfEmpty(graphics, PokeFusionLayout.BASE_SLOT, PokeFusionLayout.BASE_X, PokeFusionLayout.BASE_Y, BASE_TINT)
        repeat(FusionMaterialLogic.MAX_MATERIALS) { index ->
            tintIfEmpty(
                graphics,
                PokeFusionLayout.FIRST_MATERIAL_SLOT + index,
                PokeFusionLayout.materialX(index),
                PokeFusionLayout.materialY(index),
                MATERIAL_TINT
            )
        }
        tintIfEmpty(graphics, PokeFusionLayout.PREVIEW_SLOT, PokeFusionLayout.PREVIEW_X, PokeFusionLayout.PREVIEW_Y, PREVIEW_TINT)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        super.renderLabels(graphics, mouseX, mouseY)
        drawCentered(graphics, Component.translatable("screen.pokefusion.base"), PokeFusionLayout.BASE_X + 8, PokeFusionLayout.BASE_Y - 11)
        drawCentered(
            graphics,
            Component.translatable("screen.pokefusion.material", menu.materialCount, FusionMaterialLogic.MAX_MATERIALS),
            PokeFusionLayout.MATERIAL_X + 26,
            PokeFusionLayout.MATERIAL_Y - 11
        )
        drawCentered(graphics, Component.translatable("screen.pokefusion.result"), PokeFusionLayout.PREVIEW_X + 8, PokeFusionLayout.PREVIEW_Y - 15)
    }

    override fun renderTooltip(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val slot = hoveredSlot
        if (menu.carried.isEmpty && slot != null && !slot.hasItem() && menu.isFusionSlot(slot)) {
            val key = when (slot.containerSlot) {
                PokeFusionLayout.BASE_SLOT -> "screen.pokefusion.base"
                PokeFusionLayout.PREVIEW_SLOT -> "screen.pokefusion.result"
                else -> "screen.pokefusion.material_slot"
            }
            graphics.renderComponentTooltip(
                font,
                listOf(
                    Component.translatable(key),
                    Component.translatable("$key.hint").withStyle(ChatFormatting.GRAY)
                ),
                mouseX,
                mouseY
            )
            return
        }
        super.renderTooltip(graphics, mouseX, mouseY)
    }

    private fun drawSlot(graphics: GuiGraphics, slotX: Int, slotY: Int) {
        graphics.blit(CHEST, leftPos + slotX - 1, topPos + slotY - 1, SLOT_U, SLOT_V, 18, 18)
    }

    /** 결과 칸은 26x26으로 크게 그린다. 칸 텍스처 네 귀퉁이를 겹쳐 이어 붙인다. */
    private fun drawLargeSlot(graphics: GuiGraphics, slotX: Int, slotY: Int) {
        val left = leftPos + slotX - 5
        val top = topPos + slotY - 5
        graphics.blit(CHEST, left, top, SLOT_U, SLOT_V, 13, 13)
        graphics.blit(CHEST, left + 13, top, SLOT_U + 5, SLOT_V, 13, 13)
        graphics.blit(CHEST, left, top + 13, SLOT_U, SLOT_V + 5, 13, 13)
        graphics.blit(CHEST, left + 13, top + 13, SLOT_U + 5, SLOT_V + 5, 13, 13)
    }

    private fun tintIfEmpty(graphics: GuiGraphics, containerSlot: Int, slotX: Int, slotY: Int, color: Int) {
        if (menu.slots[containerSlot].hasItem()) return
        val left = leftPos + slotX
        val top = topPos + slotY
        graphics.fill(left, top, left + 16, top + 16, color)
    }

    private fun drawCentered(graphics: GuiGraphics, text: Component, centerX: Int, y: Int) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, LABEL_COLOR, false)
    }

    companion object {
        private val CHEST = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        private val FURNACE = ResourceLocation.withDefaultNamespace("textures/gui/container/furnace.png")

        private const val HEADER_HEIGHT = 17
        private const val BAND_V = 4
        private const val BAND_HEIGHT = 9
        private const val INVENTORY_V = 126
        private const val INVENTORY_HEIGHT = 96
        private const val SLOT_U = 7
        private const val SLOT_V = 17

        private const val LABEL_COLOR = 0x404040
        private const val BASE_TINT = 0x5040A8E0
        private const val MATERIAL_TINT = 0x5060C060
        private const val PREVIEW_TINT = 0x50E0C040
    }
}
