package kr.parkjh.pokefusion

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll

class PokeFusionPlayerStateTest {
    @Test
    fun `recovering an interrupted menu moves every input to pending output`() {
        val state = PokeFusionPlayerState(
            baseInput = ItemStack(Items.DIAMOND),
            materialInputs = listOf(ItemStack(Items.GOLD_INGOT), ItemStack(Items.IRON_INGOT)),
            pendingOutputs = listOf(ItemStack(Items.EMERALD)),
            legacyMigrationComplete = true
        )

        val recovered = state.moveInputsToPending()

        assertTrue(recovered.baseInput.isEmpty)
        assertTrue(recovered.materialInputs.isEmpty())
        assertEquals(
            listOf(Items.EMERALD, Items.DIAMOND, Items.GOLD_INGOT, Items.IRON_INGOT),
            recovered.pendingOutputs.map(ItemStack::getItem)
        )
    }

    @Test
    fun `fusion replaces inputs with result in one player state`() {
        val state = PokeFusionPlayerState(
            baseInput = ItemStack(Items.DIAMOND),
            materialInputs = listOf(ItemStack(Items.GOLD_INGOT)),
            pendingOutputs = emptyList(),
            legacyMigrationComplete = true
        )

        val completed = state.completeFusion(listOf(ItemStack(Items.EMERALD)))

        assertTrue(completed.baseInput.isEmpty)
        assertTrue(completed.materialInputs.isEmpty())
        assertEquals(listOf(Items.EMERALD), completed.pendingOutputs.map(ItemStack::getItem))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
