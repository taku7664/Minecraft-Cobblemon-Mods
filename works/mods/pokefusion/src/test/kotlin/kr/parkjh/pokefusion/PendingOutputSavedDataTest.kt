package kr.parkjh.pokefusion

import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PendingOutputSavedDataTest {
    @Test
    fun `pending item stacks survive nbt save and reload`() {
        val playerId = UUID.randomUUID()
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val original = PendingOutputSavedData()
        original.enqueue(playerId, listOf(ItemStack(Items.DIAMOND, 3), ItemStack(Items.GOLD_INGOT, 2)))

        val encoded = original.save(CompoundTag(), registries)
        val restored = PendingOutputSavedData.loadForTest(encoded, registries)

        assertEquals(listOf(Items.DIAMOND, Items.GOLD_INGOT), restored.snapshot(playerId).map(ItemStack::getItem))
        assertEquals(listOf(3, 2), restored.snapshot(playerId).map(ItemStack::getCount))
    }

    @Test
    fun `partial delivery mutation marks saved data dirty even when delivery returns false`() {
        val playerId = UUID.randomUUID()
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val source = PendingOutputSavedData().also {
            it.enqueue(playerId, listOf(ItemStack(Items.DIAMOND, 3)))
        }
        val restored = PendingOutputSavedData.loadForTest(source.save(CompoundTag(), registries), registries)
        assertFalse(restored.isDirty)

        restored.deliver(playerId) { stack ->
            stack.shrink(1)
            false
        }

        assertTrue(restored.isDirty)
        assertEquals(2, restored.snapshot(playerId).single().count)
    }

    @Test
    fun `invalid saved item disables writes and preserves the original tag`() {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val playerEntry = CompoundTag().also {
            it.putUUID("player", UUID.randomUUID())
            it.put("items", ListTag().also { items -> items.add(CompoundTag()) })
        }
        val original = CompoundTag().also {
            it.put("players", ListTag().also { players -> players.add(playerEntry) })
        }

        val restored = PendingOutputSavedData.loadForTest(original, registries)

        assertFalse(restored.isAvailable)
        assertEquals(original, restored.save(CompoundTag(), registries))
    }

    @Test
    fun `missing player id disables writes instead of silently deleting the entry`() {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val original = CompoundTag().also {
            it.put("players", ListTag().also { players ->
                players.add(CompoundTag().also { entry -> entry.put("items", ListTag()) })
            })
        }

        val restored = PendingOutputSavedData.loadForTest(original, registries)

        assertFalse(restored.isAvailable)
        assertEquals(original, restored.save(CompoundTag(), registries))
    }

    @Test
    fun `wrong items tag type disables writes instead of treating it as empty`() {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val original = CompoundTag().also {
            it.put("players", ListTag().also { players ->
                players.add(CompoundTag().also { entry ->
                    entry.putUUID("player", UUID.randomUUID())
                    entry.putString("items", "broken")
                })
            })
        }

        val restored = PendingOutputSavedData.loadForTest(original, registries)

        assertFalse(restored.isAvailable)
        assertEquals(original, restored.save(CompoundTag(), registries))
    }

    @Test
    fun `wrong item list element type disables writes instead of treating it as empty`() {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val original = CompoundTag().also {
            it.put("players", ListTag().also { players ->
                players.add(CompoundTag().also { entry ->
                    entry.putUUID("player", UUID.randomUUID())
                    entry.put("items", ListTag().also { items -> items.add(net.minecraft.nbt.StringTag.valueOf("broken")) })
                })
            })
        }

        val restored = PendingOutputSavedData.loadForTest(original, registries)

        assertFalse(restored.isAvailable)
        assertEquals(original, restored.save(CompoundTag(), registries))
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
