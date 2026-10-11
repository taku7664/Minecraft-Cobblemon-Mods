package kr.parkjh.pokefusion

import net.minecraft.ChatFormatting
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory

/**
 * 베이스 1칸, 재료 9칸, 결과 미리보기 1칸으로 된 합성 메뉴.
 * 입력은 서버가 들고 있고 칸에는 보여주기용 사본만 둔다. 클라이언트 쪽 인스턴스([serverPlayer]가 null)는
 * 화면 표시용이라 합성 칸 클릭을 예측하지 않고 서버 동기화를 기다린다.
 */
class PokeFusionMenu(
    syncId: Int,
    inventory: Inventory,
    private val serverPlayer: ServerPlayer?
) : AbstractContainerMenu(TYPE, syncId) {
    private val fusionContainer: Container = SimpleContainer(PokeFusionLayout.SIZE)
    private val state: ContainerData = SimpleContainerData(STATE_FIELDS)
    private var baseInput = ItemStack.EMPTY
    private val materialInputs = mutableListOf<ItemStack>()
    private var returnedInputs = false
    private var storageAvailable = true

    init {
        addSlot(FusionSlot(fusionContainer, PokeFusionLayout.BASE_SLOT, PokeFusionLayout.BASE_X, PokeFusionLayout.BASE_Y))
        repeat(FusionMaterialLogic.MAX_MATERIALS) { index ->
            addSlot(
                FusionSlot(
                    fusionContainer,
                    PokeFusionLayout.FIRST_MATERIAL_SLOT + index,
                    PokeFusionLayout.materialX(index),
                    PokeFusionLayout.materialY(index)
                )
            )
        }
        addSlot(FusionSlot(fusionContainer, PokeFusionLayout.PREVIEW_SLOT, PokeFusionLayout.PREVIEW_X, PokeFusionLayout.PREVIEW_Y))
        for (row in 0 until 3) {
            for (column in 0 until 9) {
                addSlot(
                    Slot(
                        inventory,
                        column + row * 9 + 9,
                        PokeFusionLayout.INVENTORY_X + column * 18,
                        PokeFusionLayout.INVENTORY_Y + row * 18
                    )
                )
            }
        }
        for (column in 0 until 9) {
            addSlot(Slot(inventory, column, PokeFusionLayout.INVENTORY_X + column * 18, PokeFusionLayout.HOTBAR_Y))
        }
        addDataSlots(state)

        if (serverPlayer != null) {
            when (val loaded = PendingOutputService.prepare(serverPlayer)) {
                null -> storageAvailable = false
                else -> {
                    baseInput = loaded.baseInput.copy()
                    materialInputs += loaded.materialInputs.map(ItemStack::copy)
                }
            }
            refreshDisplay()
        }
    }

    /** 클라이언트 화면이 읽는 버튼 상태. */
    val buttonState: Int get() = state.get(FIELD_BUTTON)
    val pendingCount: Int get() = state.get(FIELD_PENDING)
    val materialCount: Int get() = state.get(FIELD_MATERIALS)

    fun isFusionSlot(slot: Slot): Boolean = slot is FusionSlot

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id != CONFIRM_BUTTON || serverPlayer == null) return false
        confirmFusion()
        return true
    }

    override fun clicked(slotIndex: Int, button: Int, clickType: ClickType, player: Player) {
        if (slotIndex in 0 until PokeFusionLayout.SIZE) {
            if (serverPlayer == null || clickType != ClickType.PICKUP) return
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
        if (serverPlayer == null || !storageAvailable) return ItemStack.EMPTY
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
        if (serverPlayer != null) returnInputs()
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
        server().displayClientMessage(
            PokeFusionText.of("message.pokefusion.invalid_item").withStyle(ChatFormatting.RED),
            false
        )
        return false
    }

    private fun refreshDisplay() {
        val player = server()
        fusionContainer.clearContent()
        fusionContainer.setItem(PokeFusionLayout.BASE_SLOT, baseInput.copy())

        val contributions = PokeFusionService.materialContributions(player, baseInput, materialInputs)
        materialInputs.forEachIndexed { index, input ->
            val display = input.copy()
            if (contributions.getOrElse(index) { false }) {
                display.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            }
            fusionContainer.setItem(PokeFusionLayout.FIRST_MATERIAL_SLOT + index, display)
        }
        state.set(FIELD_MATERIALS, materialInputs.size)

        refreshPreview()
    }

    private fun refreshPreview() {
        val player = server()
        state.set(FIELD_PENDING, 0)
        if (!storageAvailable) {
            fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, named(ItemStack(Items.BARRIER), "item.pokefusion.storage_error", ChatFormatting.RED))
            state.set(FIELD_BUTTON, BUTTON_STORAGE_ERROR)
            return
        }
        val pendingCount = PendingOutputService.count(player)
        if (pendingCount > 0) {
            fusionContainer.setItem(
                PokeFusionLayout.PREVIEW_SLOT,
                named(ItemStack(Items.CHEST), "item.pokefusion.pending_results", ChatFormatting.YELLOW, pendingCount)
            )
            state.set(FIELD_PENDING, pendingCount)
            state.set(FIELD_BUTTON, BUTTON_RETRY)
            return
        }
        when (val result = PokeFusionService.createResult(player, baseInput, materialInputs)) {
            is PokeFusionService.Result.Success -> {
                val preview = result.item.copy()
                val oldLore = preview.get(DataComponents.LORE)?.lines() ?: emptyList()
                preview.set(
                    DataComponents.LORE,
                    ItemLore(oldLore + PokeFusionText.of("item.pokefusion.preview").withStyle(ChatFormatting.GREEN))
                )
                fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, preview)
                state.set(FIELD_BUTTON, BUTTON_CONFIRM)
            }
            is PokeFusionService.Result.Failure -> {
                fusionContainer.setItem(PokeFusionLayout.PREVIEW_SLOT, statusItem(result.reason))
                state.set(FIELD_BUTTON, BUTTON_CANNOT_FUSE)
            }
        }
    }

    private fun confirmFusion() {
        val player = server()
        if (PendingOutputService.count(player) > 0) {
            deliverPendingOutputs()
            refreshDisplay()
            broadcastChanges()
            return
        }
        when (val result = PokeFusionService.createResult(player, baseInput, materialInputs)) {
            is PokeFusionService.Result.Failure -> showFailure(result.reason)
            is PokeFusionService.Result.Success -> {
                try {
                    check(PendingOutputService.completeFusion(player, listOf(result.item) + result.materialHeldItems))
                } catch (exception: Exception) {
                    LOGGER.error("Pokefusion 결과를 저장하지 못해 합성을 취소했습니다.", exception)
                    player.displayClientMessage(
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
                if (PendingOutputService.count(player) == 0) {
                    player.displayClientMessage(
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
        if (storageAvailable && PendingOutputService.returnInputs(server())) {
            baseInput = ItemStack.EMPTY
            materialInputs.clear()
            deliverPendingOutputs()
        }
    }

    private fun deliverPendingOutputs() {
        val player = server()
        try {
            PendingOutputService.deliver(player)
        } catch (exception: Exception) {
            LOGGER.error("Pokefusion 결과 아이템을 지급하지 못했습니다. 다음 확인 때 다시 시도합니다.", exception)
        }
        if (PendingOutputService.count(player) > 0) {
            player.displayClientMessage(
                PokeFusionText.of("message.pokefusion.delivery_incomplete")
                    .withStyle(ChatFormatting.RED),
                false
            )
        }
    }

    private fun persistInputs(): Boolean = try {
        if (!PendingOutputService.storeInputs(server(), baseInput, materialInputs)) {
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
        server().displayClientMessage(
            PokeFusionText.of("message.pokefusion.store_input_failed").withStyle(ChatFormatting.RED),
            false
        )
    }

    private fun statusItem(reason: PokeFusionService.ValidationFailure): ItemStack {
        // 아직 덜 넣은 상태는 오류가 아니므로 결과 칸을 비워 둔다.
        if (reason == PokeFusionService.ValidationFailure.MISSING_INPUT) return ItemStack.EMPTY
        return named(ItemStack(Items.BARRIER), failureKey("item", reason), ChatFormatting.RED)
    }

    private fun showFailure(reason: PokeFusionService.ValidationFailure) {
        server().displayClientMessage(
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
        // CUSTOM_NAME은 기울임꼴로 보이므로 ITEM_NAME을 쓴다.
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

    private fun server(): ServerPlayer = checkNotNull(serverPlayer) { "합성 처리는 서버에서만 한다" }

    /** 합성 칸은 직접 집거나 놓지 못하고, 클릭은 메뉴가 처리한다. */
    private class FusionSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
    }

    companion object {
        const val CONFIRM_BUTTON = 0

        const val BUTTON_CANNOT_FUSE = 0
        const val BUTTON_CONFIRM = 1
        const val BUTTON_RETRY = 2
        const val BUTTON_STORAGE_ERROR = 3

        private const val FIELD_BUTTON = 0
        private const val FIELD_PENDING = 1
        private const val FIELD_MATERIALS = 2
        private const val STATE_FIELDS = 3

        private val LOGGER = LoggerFactory.getLogger(PokeFusionMenu::class.java)

        val TYPE: MenuType<PokeFusionMenu> = Registry.register(
            BuiltInRegistries.MENU,
            ResourceLocation.fromNamespaceAndPath("pokefusion", "fusion"),
            MenuType({ syncId, inventory -> PokeFusionMenu(syncId, inventory, null) }, FeatureFlags.VANILLA_SET)
        )

        /** 모드 초기화 때 불러 메뉴 종류를 등록한다. */
        fun register() = Unit
    }
}
