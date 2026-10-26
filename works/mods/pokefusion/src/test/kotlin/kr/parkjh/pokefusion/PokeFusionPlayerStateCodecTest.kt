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
    fun `uncertain delivery hold survives reload while legacy records remain unblocked`() {
        val state = PokeFusionPlayerState(pendingOutputs = listOf(ItemStack(Items.DIAMOND)), legacyMigrationComplete = true, deliveryBlocked = true)
        val encoded = PokeFusionPlayerStateCodec.encode(state, registries)
        val restored = PokeFusionPlayerStateCodec.decode(encoded, registries)
        assertEquals(true, restored.deliveryBlocked)
        assertEquals(1, restored.pendingOutputs.size)
        assertEquals(true, restored.withInputs(ItemStack.EMPTY, emptyList()).deliveryBlocked)
        assertEquals(true, restored.moveInputsToPending().deliveryBlocked)
        assertEquals(true, restored.completeFusion(emptyList()).deliveryBlocked)
        encoded.remove("deliveryBlocked")
        assertEquals(false, PokeFusionPlayerStateCodec.decode(encoded, registries).deliveryBlocked)
        encoded.putString("deliveryBlocked", "false")
        assertThrows(IllegalArgumentException::class.java) { PokeFusionPlayerStateCodec.decode(encoded, registries) }
    }

    @Test
    fun `malformed input state types versions and material overflow are rejected`() {
        val original = PokeFusionPlayerStateCodec.encode(PokeFusionPlayerState(legacyMigrationComplete = true), registries)
        val corrupt = listOf(
            original.copy().apply { remove("version") },
            original.copy().apply { putInt("version", 99) },
            original.copy().apply { putString("base", "broken") },
            original.copy().apply { remove("materials") },
            original.copy().apply { putString("pending", "broken") },
            original.copy().apply { remove("legacyMigrationComplete") },
            original.copy().apply { putString("legacyMigrationComplete", "true") },
            original.copy().apply { put("base", net.minecraft.nbt.CompoundTag()) },
            original.copy().apply { put("materials", ListTag().also { list -> repeat(10) { list.add(ItemStack(Items.DIAMOND).save(registries)) } }) },
        )
        for (root in corrupt) {
            val before = root.copy()
            assertThrows(IllegalArgumentException::class.java) { PokeFusionPlayerStateCodec.decode(root, registries) }
            assertEquals(before, root)
        }
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
