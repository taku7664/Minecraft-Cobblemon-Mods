package kr.parkjh.pokefusion

import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PokeFusionPlayerStateCodecTest {
    @Test
    fun `player state survives nbt round trip`() {
        val state = PokeFusionPlayerState(
            baseInput = ItemStack(Items.DIAMOND, 1),
            materialInputs = listOf(ItemStack(Items.GOLD_INGOT, 2)),
            pendingOutputs = listOf(ItemStack(Items.EMERALD, 3)),
            legacyMigrationComplete = true
        )

        val restored = PokeFusionPlayerStateCodec.decode(
            PokeFusionPlayerStateCodec.encode(state, registries),
            registries
        )

        assertEquals(Items.DIAMOND, restored.baseInput.item)
        assertEquals(Items.GOLD_INGOT, restored.materialInputs.single().item)
        assertEquals(2, restored.materialInputs.single().count)
        assertEquals(Items.EMERALD, restored.pendingOutputs.single().item)
        assertEquals(3, restored.pendingOutputs.single().count)
        assertEquals(true, restored.legacyMigrationComplete)
    }

    @Test
    fun `wrong pending list element type is rejected`() {
        val root = PokeFusionPlayerStateCodec.encode(PokeFusionPlayerState(legacyMigrationComplete = true), registries)
        root.put("pending", ListTag().also { it.add(StringTag.valueOf("broken")) })

        assertThrows(IllegalArgumentException::class.java) {
            PokeFusionPlayerStateCodec.decode(root, registries)
        }
    }

    companion object {
        private lateinit var registries: HolderLookup.Provider

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        }
    }
}
