package kr.parkjh.pokefusion

import net.minecraft.world.item.ItemStack

data class PokeFusionPlayerState(
    val baseInput: ItemStack = ItemStack.EMPTY,
    val materialInputs: List<ItemStack> = emptyList(),
    val pendingOutputs: List<ItemStack> = emptyList(),
    val legacyMigrationComplete: Boolean = false
) {
    fun withInputs(base: ItemStack, materials: List<ItemStack>): PokeFusionPlayerState = copy(
        baseInput = base.safeCopy(),
        materialInputs = materials.safeCopies()
    )

    fun moveInputsToPending(): PokeFusionPlayerState = copy(
        baseInput = ItemStack.EMPTY,
        materialInputs = emptyList(),
        pendingOutputs = (pendingOutputs + listOf(baseInput) + materialInputs).safeCopies()
    )

    fun completeFusion(outputs: List<ItemStack>): PokeFusionPlayerState = copy(
        baseInput = ItemStack.EMPTY,
        materialInputs = emptyList(),
        pendingOutputs = (pendingOutputs + outputs).safeCopies()
    )

    fun withPending(outputs: List<ItemStack>): PokeFusionPlayerState = copy(pendingOutputs = outputs.safeCopies())

    fun importLegacy(outputs: List<ItemStack>): PokeFusionPlayerState = copy(
        pendingOutputs = (pendingOutputs + outputs).safeCopies(),
        legacyMigrationComplete = true
    )

    private fun ItemStack.safeCopy(): ItemStack = if (isEmpty) ItemStack.EMPTY else copy()

    private fun Iterable<ItemStack>.safeCopies(): List<ItemStack> =
        filterNot(ItemStack::isEmpty).map(ItemStack::copy)
}
