package kr.parkjh.pokefusion

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory

class PokeFusionMenu(
    syncId: Int,
    inventory: Inventory,
    private val serverPlayer: ServerPlayer
) : ChestMenu(MenuType.GENERIC_9x6, syncId, inventory, SimpleContainer(SIZE), ROWS) {
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
        if (slotIndex == CONFIRM_SLOT && clickType == ClickType.PICKUP) {
            confirmFusion()
            return
        }

        if (slotIndex in 0 until SIZE) {
            if (clickType != ClickType.PICKUP) return
            when {
                slotIndex == BASE_SLOT -> handleBaseClick()
                slotIndex in FusionMaterialLogic.visibleSlots(materialInputs.size) -> handleMaterialClick(slotIndex)
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
        if (index < SIZE || index >= slots.size) return ItemStack.EMPTY
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
        val displayedSlots = FusionMaterialLogic.visibleSlots(materialInputs.size)
        val materialIndex = displayedSlots.indexOf(slotIndex)
        if (materialIndex < 0) return

        val cursor = carried
        when {
            materialIndex < materialInputs.size && cursor.isEmpty -> {
                val removed = materialInputs.removeAt(materialIndex)
                if (persistInputs()) setCarried(removed) else materialInputs.add(materialIndex, removed)
            }
            materialIndex == materialInputs.size && !cursor.isEmpty && acceptPokemon(cursor) -> {
                materialInputs += cursor.copy()
                if (persistInputs()) setCarried(ItemStack.EMPTY) else materialInputs.removeLast()
            }
        }
    }

    private fun acceptPokemon(stack: ItemStack): Boolean {
        if (PokeFusionService.isPokemonItem(stack)) return true
        serverPlayer.displayClientMessage(
            Component.translatable("message.pokefusion.invalid_item").withStyle(ChatFormatting.RED),
            false
        )
        return false
    }

    private fun refreshDisplay() {
        fusionContainer.clearContent()

        fusionContainer.setItem(BASE_SLOT, baseInput.copy())

        val contributions = PokeFusionService.materialContributions(serverPlayer, baseInput, materialInputs)
        val displayedSlots = FusionMaterialLogic.visibleSlots(materialInputs.size)
        materialInputs.forEachIndexed { index, input ->
            val display = input.copy()
            if (contributions.getOrElse(index) { false }) {
                display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            }
            fusionContainer.setItem(displayedSlots[index], display)
        }
        if (materialInputs.size < FusionMaterialLogic.MAX_MATERIALS) {
            fusionContainer.setItem(displayedSlots.last(), ItemStack.EMPTY)
        }

        refreshPreview()
    }

    private fun refreshPreview() {
        if (!storageAvailable) {
            fusionContainer.setItem(PREVIEW_SLOT, named(ItemStack(Items.BARRIER), "item.pokefusion.storage_error", ChatFormatting.RED))
            fusionContainer.setItem(CONFIRM_SLOT, named(ItemStack(Items.GRAY_DYE), "item.pokefusion.cannot_fuse", ChatFormatting.GRAY))
            return
        }
        val pendingCount = PendingOutputService.count(serverPlayer)
        if (pendingCount > 0) {
            fusionContainer.setItem(
                PREVIEW_SLOT,
                named(ItemStack(Items.CHEST), "item.pokefusion.pending_results", ChatFormatting.YELLOW, pendingCount)
            )
            fusionContainer.setItem(CONFIRM_SLOT, named(ItemStack(Items.LIME_DYE), "item.pokefusion.retry_results", ChatFormatting.GREEN))
            return
        }
        when (val result = PokeFusionService.createResult(serverPlayer, baseInput, materialInputs)) {
            is PokeFusionService.Result.Success -> {
                val preview = result.item.copy()
                val oldLore = preview.get(DataComponents.LORE)?.lines() ?: emptyList()
                preview.set(
                    DataComponents.LORE,
                    ItemLore(oldLore + Component.translatable("item.pokefusion.preview").withStyle(ChatFormatting.GREEN))
                )
                fusionContainer.setItem(PREVIEW_SLOT, preview)
                fusionContainer.setItem(CONFIRM_SLOT, named(ItemStack(Items.LIME_DYE), "item.pokefusion.confirm", ChatFormatting.GREEN))
            }
            is PokeFusionService.Result.Failure -> {
                fusionContainer.setItem(PREVIEW_SLOT, statusItem(result.reason))
                fusionContainer.setItem(CONFIRM_SLOT, named(ItemStack(Items.GRAY_DYE), "item.pokefusion.cannot_fuse", ChatFormatting.GRAY))
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
                        Component.translatable("message.pokefusion.store_result_failed")
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
                        Component.translatable("message.pokefusion.completed").withStyle(ChatFormatting.GREEN),
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
                Component.translatable("message.pokefusion.delivery_incomplete")
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
            Component.translatable("message.pokefusion.store_input_failed").withStyle(ChatFormatting.RED),
            false
        )
    }

    private fun statusItem(reason: PokeFusionService.ValidationFailure): ItemStack {
        return named(ItemStack(Items.BARRIER), failureKey("item", reason), ChatFormatting.RED)
    }

    private fun showFailure(reason: PokeFusionService.ValidationFailure) {
        serverPlayer.displayClientMessage(
            Component.translatable(failureKey("message", reason)).withStyle(ChatFormatting.RED),
            false
        )
    }

    private fun named(
        stack: ItemStack,
        translationKey: String,
        color: ChatFormatting? = null,
        vararg arguments: Any
    ): ItemStack {
        var component = Component.translatable(translationKey, *arguments)
        if (color != null) component = component.withStyle(color)
        stack.set(DataComponents.CUSTOM_NAME, component)
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
        private const val ROWS = 6
        private const val SIZE = ROWS * 9
        private const val BASE_SLOT = 11
        private const val PREVIEW_SLOT = 15
        private const val CONFIRM_SLOT = 49
        private val LOGGER = LoggerFactory.getLogger(PokeFusionMenu::class.java)
    }
}
