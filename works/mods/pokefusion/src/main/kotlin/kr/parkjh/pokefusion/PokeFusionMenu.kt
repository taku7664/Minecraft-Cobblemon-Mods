package kr.parkjh.pokefusion

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Unit as MinecraftUnit
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory

class PokeFusionMenu(
    syncId: Int,
    inventory: Inventory,
    private val serverPlayer: ServerPlayer
) : ChestMenu(MenuType.GENERIC_9x6, syncId, inventory, SimpleContainer(PokeFusionLayout.SIZE), PokeFusionLayout.ROWS) {
    private val fusionContainer: Container = getContainer()
    private var baseInput = ItemStack.EMPTY
    private val materialInputs = mutableListOf<ItemStack>()
    private var returnedInputs = false
    private var storageAvailable = true

    init {
        when (val loaded = PendingOutputService.prepare(serverPlayer)) {
            null -> storageAvailable = false
            else -> {
                baseInput = loaded.baseInput.copy()
                materialInputs += loaded.materialInputs.map(ItemStack::copy)
            }
        }
        refreshDisplay()
    }

    override fun clicked(slotIndex: Int, button: Int, clickType: ClickType, player: Player) {
        if (slotIndex == PokeFusionLayout.CONFIRM_SLOT && clickType == ClickType.PICKUP) {
            confirmFusion()
            return
        }

        if (slotIndex in 0 until PokeFusionLayout.SIZE) {
            if (clickType != ClickType.PICKUP) return
            when {
                slotIndex == PokeFusionLayout.BASE_SLOT -> handleBaseClick()
                PokeFusionLayout.materialIndexOf(slotIndex) >= 0 -> handleMaterialClick(slotIndex)
                else -> return
            }
            refreshDisplay()
            broadcastChanges()
            return
        }

        if (clickType == ClickType.PICKUP_ALL || clickType == ClickType.QUICK_CRAFT) return
        super.clicked(slotIndex, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        if (!storageAvailable) return ItemStack.EMPTY
        if (index < PokeFusionLayout.SIZE || index >= slots.size) return ItemStack.EMPTY
        val sourceSlot = slots[index]
        val sourceStack = sourceSlot.item
        if (!PokeFusionService.isPokemonItem(sourceStack)) return ItemStack.EMPTY

        val destination = FusionMaterialLogic.nextDestination(baseInput.isEmpty, materialInputs.size)
        if (destination == FusionMaterialLogic.InputDestination.NONE) return ItemStack.EMPTY

        val moved = sourceStack.copy()
        when (destination) {
            FusionMaterialLogic.InputDestination.BASE -> baseInput = moved
            FusionMaterialLogic.InputDestination.MATERIAL -> materialInputs += moved
            FusionMaterialLogic.InputDestination.NONE -> return ItemStack.EMPTY
        }
        if (!persistInputs()) {
            when (destination) {
                FusionMaterialLogic.InputDestination.BASE -> baseInput = ItemStack.EMPTY
                FusionMaterialLogic.InputDestination.MATERIAL -> materialInputs.removeLast()
                FusionMaterialLogic.InputDestination.NONE -> Unit
            }
            return ItemStack.EMPTY
        }
        sourceSlot.set(ItemStack.EMPTY)
        sourceSlot.setChanged()
        refreshDisplay()
        broadcastChanges()
        return moved
    }

    override fun removed(player: Player) {
        returnInputs()
        super.removed(player)
    }

    override fun stillValid(player: Player): Boolean = true

    private fun handleBaseClick() {
        if (!storageAvailable) return
        val cursor = carried
        when {
            cursor.isEmpty && !baseInput.isEmpty -> {
                val removed = baseInput
                baseInput = ItemStack.EMPTY
                if (persistInputs()) setCarried(removed) else baseInput = removed
            }
            !cursor.isEmpty && baseInput.isEmpty && acceptPokemon(cursor) -> {
                baseInput = cursor.copy()
                if (persistInputs()) setCarried(ItemStack.EMPTY) else baseInput = ItemStack.EMPTY
            }
        }
    }

    private fun handleMaterialClick(slotIndex: Int) {
        if (!storageAvailable) return
        val materialIndex = PokeFusionLayout.materialIndexOf(slotIndex)
        if (materialIndex < 0) return

        val cursor = carried
        when {
            materialIndex < materialInputs.size && cursor.isEmpty -> {
                val removed = materialInputs.removeAt(materialIndex)
                if (persistInputs()) setCarried(removed) else materialInputs.add(materialIndex, removed)
            }
            // 빈 재료 칸 어디를 눌러도 다음 순서 칸에 채운다.
            materialIndex >= materialInputs.size && materialInputs.size < FusionMaterialLogic.MAX_MATERIALS &&
                !cursor.isEmpty && acceptPokemon(cursor) -> {
                materialInputs += cursor.copy()
                if (persistInputs()) setCarried(ItemStack.EMPTY) else materialInputs.removeLast()
            }
        }
    }

    private fun acceptPokemon(stack: ItemStack): Boolean {
        if (PokeFusionService.isPokemonItem(stack)) return true
        serverPlayer.displayClientMessage(
            PokeFusionText.of("message.pokefusion.invalid_item").withStyle(ChatFormatting.RED),
            false
        )
        return false
    }

    private fun refreshDisplay() {
        fusionContainer.clearContent()
        drawFrame()

        fusionContainer.setItem(
            PokeFusionLayout.BASE_SLOT,
            if (baseInput.isEmpty) emptySlot(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "item.pokefusion.slot.base", ChatFormatting.AQUA)
            else baseInput.copy()
        )

        val contributions = PokeFusionService.materialContributions(serverPlayer, baseInput, materialInputs)
        PokeFusionLayout.MATERIAL_SLOTS.forEachIndexed { index, slot ->
            val input = materialInputs.getOrNull(index)
            val display = if (input == null) {
                emptySlot(Items.LIME_STAINED_GLASS_PANE, "item.pokefusion.slot.material", ChatFormatting.GREEN)
            } else {
                input.copy().also {
                    if (contributions.getOrElse(index) { false }) it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                }
            }
            fusionContainer.setItem(slot, display)
        }

        refreshPreview()
    }

    private fun drawFrame() {
        PokeFusionLayout.decorationSlots().forEach { slot ->
            fusionContainer.setItem(
                slot,
                ItemStack(Items.GRAY_STAINED_GLASS_PANE).also { it.set(DataComponents.HIDE_TOOLTIP, MinecraftUnit.INSTANCE) }
            )
        }
        fusionContainer.setItem(
            PokeFusionLayout.BASE_LABEL_SLOT,
            label(Items.LIGHT_BLUE_STAINED_GLASS, "item.pokefusion.label.base", ChatFormatting.AQUA)
        )
        fusionContainer.setItem(
            PokeFusionLayout.MATERIAL_LABEL_SLOT,
            label(
                Items.LIME_STAINED_GLASS,
                "item.pokefusion.label.material",
                ChatFormatting.GREEN,
                materialInputs.size,
                FusionMaterialLogic.MAX_MATERIALS
            )
        )
        fusionContainer.setItem(
            PokeFusionLayout.PREVIEW_LABEL_SLOT,
            label(Items.YELLOW_STAINED_GLASS, "item.pokefusion.label.preview", ChatFormatting.YELLOW)
        )
        fusionContainer.setItem(PokeFusionLayout.ARROW_SLOT, named(ItemStack(Items.ARROW), "item.pokefusion.arrow", ChatFormatting.WHITE))
    }

    private fun label(item: Item, key: String, color: ChatFormatting, vararg arguments: Any): ItemStack =
        withHint(named(ItemStack(item), key, color, *arguments), "$key.hint")

    private fun emptySlot(item: Item, key: String, color: ChatFormatting): ItemStack =
        withHint(named(ItemStack(item), key, color), "$key.hint")

    private fun withHint(stack: ItemStack, hintKey: String): ItemStack {
        val line = PokeFusionText.of(hintKey).withStyle { it.withColor(ChatFormatting.GRAY).withItalic(false) }
        stack.set(DataComponents.LORE, ItemLore(listOf(line)))
        return stack
    }

    private fun refreshPreview() {
        if (!storageAvailable) {
            fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, named(ItemStack(Items.BARRIER), "item.pokefusion.storage_error", ChatFormatting.RED))
            fusionContainer.setItem(PokeFusionLayout.CONFIRM_SLOT, named(ItemStack(Items.GRAY_DYE), "item.pokefusion.cannot_fuse", ChatFormatting.GRAY))
            return
        }
        val pendingCount = PendingOutputService.count(serverPlayer)
        if (pendingCount > 0) {
            fusionContainer.setItem(
                PokeFusionLayout.PREVIEW_SLOT,
                named(ItemStack(Items.CHEST), "item.pokefusion.pending_results", ChatFormatting.YELLOW, pendingCount)
            )
            fusionContainer.setItem(PokeFusionLayout.CONFIRM_SLOT, named(ItemStack(Items.LIME_DYE), "item.pokefusion.retry_results", ChatFormatting.GREEN))
            return
        }
        when (val result = PokeFusionService.createResult(serverPlayer, baseInput, materialInputs)) {
            is PokeFusionService.Result.Success -> {
                val preview = result.item.copy()
                val oldLore = preview.get(DataComponents.LORE)?.lines() ?: emptyList()
                preview.set(
                    DataComponents.LORE,
                    ItemLore(oldLore + PokeFusionText.of("item.pokefusion.preview").withStyle(ChatFormatting.GREEN))
                )
                fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, preview)
                fusionContainer.setItem(PokeFusionLayout.CONFIRM_SLOT, named(ItemStack(Items.LIME_DYE), "item.pokefusion.confirm", ChatFormatting.GREEN))
            }
            is PokeFusionService.Result.Failure -> {
                fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, statusItem(result.reason))
                fusionContainer.setItem(PokeFusionLayout.CONFIRM_SLOT, named(ItemStack(Items.GRAY_DYE), "item.pokefusion.cannot_fuse", ChatFormatting.GRAY))
            }
        }
    }

    private fun confirmFusion() {
        if (PendingOutputService.count(serverPlayer) > 0) {
            deliverPendingOutputs()
            refreshDisplay()
            broadcastChanges()
            return
        }
        when (val result = PokeFusionService.createResult(serverPlayer, baseInput, materialInputs)) {
            is PokeFusionService.Result.Failure -> showFailure(result.reason)
            is PokeFusionService.Result.Success -> {
                try {
                    check(PendingOutputService.completeFusion(serverPlayer, listOf(result.item) + result.materialHeldItems))
                } catch (exception: Exception) {
                    LOGGER.error("Pokefusion 결과를 저장하지 못해 합성을 취소했습니다.", exception)
                    serverPlayer.displayClientMessage(
                        PokeFusionText.of("message.pokefusion.store_result_failed")
                            .withStyle(ChatFormatting.RED),
                        false
                    )
                    refreshDisplay()
                    broadcastChanges()
                    return
                }
                baseInput = ItemStack.EMPTY
                materialInputs.clear()
                deliverPendingOutputs()
                if (PendingOutputService.count(serverPlayer) == 0) {
                    serverPlayer.displayClientMessage(
                        PokeFusionText.of("message.pokefusion.completed").withStyle(ChatFormatting.GREEN),
                        false
                    )
                }
            }
        }
        refreshDisplay()
        broadcastChanges()
    }

    private fun returnInputs() {
        if (returnedInputs) return
        returnedInputs = true
        if (storageAvailable && PendingOutputService.returnInputs(serverPlayer)) {
            baseInput = ItemStack.EMPTY
            materialInputs.clear()
            deliverPendingOutputs()
        }
    }

    private fun deliverPendingOutputs() {
        try {
            PendingOutputService.deliver(serverPlayer)
        } catch (exception: Exception) {
            LOGGER.error("Pokefusion 결과 아이템을 지급하지 못했습니다. 다음 확인 때 다시 시도합니다.", exception)
        }
        if (PendingOutputService.count(serverPlayer) > 0) {
            serverPlayer.displayClientMessage(
                PokeFusionText.of("message.pokefusion.delivery_incomplete")
                    .withStyle(ChatFormatting.RED),
                false
            )
        }
    }

    private fun persistInputs(): Boolean = try {
        if (!PendingOutputService.storeInputs(serverPlayer, baseInput, materialInputs)) {
            storageAvailable = false
            showStorageFailure()
            false
        } else {
            true
        }
    } catch (exception: Exception) {
        storageAvailable = false
        LOGGER.error("Pokefusion 입력 아이템을 저장하지 못했습니다.", exception)
        showStorageFailure()
        false
    }

    private fun showStorageFailure() {
        serverPlayer.displayClientMessage(
            PokeFusionText.of("message.pokefusion.store_input_failed").withStyle(ChatFormatting.RED),
            false
        )
    }

    private fun statusItem(reason: PokeFusionService.ValidationFailure): ItemStack {
        // 아직 덜 넣은 상태는 오류가 아니므로 결과 칸 자리표시로 보여준다.
        if (reason == PokeFusionService.ValidationFailure.MISSING_INPUT) {
            return emptySlot(Items.YELLOW_STAINED_GLASS_PANE, "item.pokefusion.slot.preview", ChatFormatting.YELLOW)
        }
        return named(ItemStack(Items.BARRIER), failureKey("item", reason), ChatFormatting.RED)
    }

    private fun showFailure(reason: PokeFusionService.ValidationFailure) {
        serverPlayer.displayClientMessage(
            PokeFusionText.of(failureKey("message", reason)).withStyle(ChatFormatting.RED),
            false
        )
    }

    private fun named(
        stack: ItemStack,
        translationKey: String,
        color: ChatFormatting? = null,
        vararg arguments: Any
    ): ItemStack {
        var component = PokeFusionText.of(translationKey, *arguments)
        if (color != null) component = component.withStyle(color)
        stack.set(DataComponents.ITEM_NAME, component)
        return stack
    }

    private fun failureKey(prefix: String, reason: PokeFusionService.ValidationFailure): String {
        val suffix = when (reason) {
            PokeFusionService.ValidationFailure.MISSING_INPUT -> "missing_input"
            PokeFusionService.ValidationFailure.INVALID_ITEM -> "invalid_item"
            PokeFusionService.ValidationFailure.DIFFERENT_EVOLUTION_FAMILY -> "different_family"
            PokeFusionService.ValidationFailure.DIFFERENT_FORM -> "different_form"
            PokeFusionService.ValidationFailure.PROCESSING_ERROR -> "processing_error"
        }
        return "$prefix.pokefusion.failure.$suffix"
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PokeFusionMenu::class.java)
    }
}
