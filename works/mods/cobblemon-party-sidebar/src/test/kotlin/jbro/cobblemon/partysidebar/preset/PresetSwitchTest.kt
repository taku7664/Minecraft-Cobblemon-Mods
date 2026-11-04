package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.SharedConstants
import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round trips through preset switches on real Cobblemon party stores, counting every copy of every item and Pokemon:
 * the item storage, the party's held items, the Pokemon storage (and the items packed inside its Pokemon) and the
 * player inventory. A switch may move things but must never make or lose one.
 */
class PresetSwitchTest {
    private class World {
        val party = object : PartyStore(UUID.randomUUID()) {
            override fun getObservingPlayers(): List<ServerPlayer> = emptyList()
        }
        val items: NonNullList<ItemStack> = NonNullList.withSize(PresetData.STORAGE_SIZE, ItemStack.EMPTY)
        val pokemonItems: NonNullList<ItemStack> = NonNullList.withSize(PresetData.STORAGE_SIZE, ItemStack.EMPTY)
        val itemStorage = StorageContainer(items, StorageKind.ITEMS) {}
        val pokemonStorage = StorageContainer(pokemonItems, StorageKind.POKEMON) {}
        val inventory = SimpleContainer(36)
        val switch = PresetSwitch(party, emptyList(), pokemonItems, pokemonStorage, itemStorage, inventory, Packer)

        fun storedIds(): List<UUID> = pokemonItems.mapNotNull(Packer::uuid) +
            (0 until inventory.containerSize).mapNotNull { Packer.uuid(inventory.getItem(it)) }

        /** Mirrors [preset] the way the server does before every switch. */
        fun mirror(preset: Preset) = PresetSwitch.mirror(preset, party) { id ->
            party.any { it.uuid == id } || pokemonItems.any { Packer.uuid(it) == id }
        }

        fun lend(pokemon: Pokemon, item: Item) {
            val lent = itemStorage.takeOne(ItemStack(item))
            assertFalse(lent.isEmpty, "the storage must hold the item to lend")
            pokemon.swapHeldItem(lent, false, false)
            assertTrue(StoredMark.isStored(pokemon.heldItem()))
        }

        /** Every [item] anywhere: storage, held, packed inside stored Pokemon, inventory. */
        fun count(item: Item): Int {
            fun held(stacks: Iterable<ItemStack>) = stacks.sumOf { stack ->
                (if (stack.`is`(item)) stack.count else 0) +
                    (Packer.unpack(stack)?.heldItem()?.takeIf { it.`is`(item) }?.count ?: 0)
            }
            return held(items) + party.sumOf { pokemon -> pokemon.heldItem().takeIf { it.`is`(item) }?.count ?: 0 } +
                held(pokemonItems) + held((0 until inventory.containerSize).map(inventory::getItem))
        }

        /** Each Pokemon exactly once across the party, the Pokemon storage and the inventory. */
        fun assertEachOnce(vararg pokemon: Pokemon) {
            val everywhere = party.map { it.uuid } + storedIds()
            for (one in pokemon) assertEquals(1, everywhere.count { it == one.uuid }, "${one.uuid} must exist exactly once")
            assertEquals(pokemon.size, everywhere.size, "no Pokemon may appear from nowhere")
        }

        fun run(target: Preset) {
            val result = switch.run(target)
            assertTrue(result is PresetSwitch.Result.Done, "switch refused: $result")
            mirror(target)
        }
    }

    private fun preset(vararg pokemon: Pokemon?) =
        Preset(List(PresetData.PARTY_SIZE) { PresetSlot(pokemon.getOrNull(it)?.uuid) })

    @Test fun `a borrowed item is not copied by switching to a preset without its Pokemon and back`() {
        val world = World()
        val owl = Pokemon()
        val other = Pokemon()
        world.party.set(PartyPosition(0), owl)
        world.party.set(PartyPosition(1), other)
        world.itemStorage.add(ItemStack(Items.IRON_HELMET))
        world.lend(owl, Items.IRON_HELMET)
        val first = Preset().also { world.mirror(it) }
        val second = preset(other)

        repeat(5) {
            world.run(second)
            assertEquals(1, world.count(Items.IRON_HELMET), "after leaving: round ${it + 1}")
            assertEquals(other.uuid, world.party.get(0)?.uuid)
            world.assertEachOnce(owl, other)
            world.run(first)
            assertEquals(1, world.count(Items.IRON_HELMET), "after coming back: round ${it + 1}")
            world.assertEachOnce(owl, other)
        }
        val back = world.party.get(0)!!
        assertEquals(owl.uuid, back.uuid)
        assertTrue(back.heldItem().`is`(Items.IRON_HELMET) && StoredMark.isStored(back.heldItem()), "the helmet is lent again")
    }

    @Test fun `an own held item travels inside the Pokemon and is never copied into the storage`() {
        val world = World()
        val owl = Pokemon()
        val other = Pokemon()
        world.party.set(PartyPosition(0), owl)
        world.party.set(PartyPosition(1), other)
        owl.swapHeldItem(ItemStack(Items.APPLE), false, false)
        val first = Preset().also { world.mirror(it) }
        val second = preset(other)

        repeat(3) {
            world.run(second)
            world.run(first)
        }
        assertEquals(1, world.count(Items.APPLE))
        assertTrue(world.items.all(ItemStack::isEmpty), "an own item never enters the storage")
        assertFalse(StoredMark.isStored(world.party.get(0)!!.heldItem()))
    }

    @Test fun `an empty preset puts the whole party away and switching back restores it with its loans`() {
        val world = World()
        val a = Pokemon()
        val b = Pokemon()
        val c = Pokemon()
        world.party.set(PartyPosition(0), a)
        world.party.set(PartyPosition(1), b)
        world.party.set(PartyPosition(2), c)
        world.itemStorage.add(ItemStack(Items.IRON_HELMET, 2))
        world.lend(a, Items.IRON_HELMET)
        world.lend(c, Items.IRON_HELMET)
        b.swapHeldItem(ItemStack(Items.APPLE), false, false)
        val first = Preset().also { world.mirror(it) }

        world.run(Preset())
        assertTrue(world.party.none { true }, "the empty preset has no Pokemon")
        assertEquals(2, world.count(Items.IRON_HELMET))
        assertEquals(1, world.count(Items.APPLE))
        world.assertEachOnce(a, b, c)

        world.run(first)
        assertEquals(listOf(a.uuid, b.uuid, c.uuid), (0..2).map { world.party.get(it)?.uuid })
        assertEquals(2, world.count(Items.IRON_HELMET))
        assertEquals(1, world.count(Items.APPLE))
        assertTrue((0..2).all { !world.party.get(it)!!.heldItem().isEmpty })
    }

    @Test fun `a Pokemon itemized into the inventory comes back from there, once`() {
        val world = World()
        val owl = Pokemon()
        val other = Pokemon()
        world.party.set(PartyPosition(0), owl)
        world.party.set(PartyPosition(1), other)
        world.itemStorage.add(ItemStack(Items.IRON_HELMET))
        world.lend(owl, Items.IRON_HELMET)
        val first = Preset().also { world.mirror(it) }
        val second = preset(other)
        world.run(second)

        // On the second preset the player itemizes the other Pokemon into the inventory, as the sidebar does.
        val item = Packer.pack(world.party.get(0)!!)
        world.party.remove(world.party.get(0)!!)
        world.inventory.setItem(5, item)
        world.mirror(second)
        assertEquals(other.uuid, second.slots[0].pokemon, "a Pokemon in the inventory keeps its row")

        world.run(first)
        assertEquals(listOf(owl.uuid, other.uuid), (0..1).map { world.party.get(it)?.uuid })
        assertTrue(world.inventory.getItem(5).isEmpty, "the inventory item was taken, not copied")
        assertEquals(1, world.count(Items.IRON_HELMET))
        world.assertEachOnce(owl, other)
    }

    @Test fun `a refused switch moves nothing`() {
        val world = World()
        val owl = Pokemon()
        world.party.set(PartyPosition(0), owl)
        world.itemStorage.add(ItemStack(Items.IRON_HELMET))
        world.lend(owl, Items.IRON_HELMET)
        Preset().also { world.mirror(it) }
        // A full Pokemon storage has no room for the Pokemon that would leave.
        repeat(PresetData.STORAGE_SIZE) { world.pokemonItems[it] = Packer.pack(Pokemon()) }

        val result = world.switch.run(Preset())
        assertEquals(PresetSwitch.Result.Refused("pokemon_storage_full"), result)
        assertSame(owl, world.party.get(0))
        assertTrue(StoredMark.isStored(owl.heldItem()), "the loan stays on the Pokemon")
        assertEquals(1, world.count(Items.IRON_HELMET))
    }

    @Test fun `two Pokemon wanting one lent item share it without making a second`() {
        val world = World()
        val a = Pokemon()
        val b = Pokemon()
        world.party.set(PartyPosition(0), a)
        world.itemStorage.add(ItemStack(Items.IRON_HELMET))
        world.lend(a, Items.IRON_HELMET)
        val first = Preset().also { world.mirror(it) }
        // The second preset wants the same helmet on b.
        val second = Preset(List(PresetData.PARTY_SIZE) {
            if (it == 0) PresetSlot(b.uuid, StoredMark.prototype(ItemStack(Items.IRON_HELMET))) else PresetSlot()
        })
        world.pokemonItems[0] = Packer.pack(b)

        repeat(3) {
            world.run(second)
            assertEquals(1, world.count(Items.IRON_HELMET))
            assertTrue(world.party.get(0)!!.heldItem().`is`(Items.IRON_HELMET))
            world.run(first)
            assertEquals(1, world.count(Items.IRON_HELMET))
            assertTrue(world.party.get(0)!!.heldItem().`is`(Items.IRON_HELMET))
        }
        world.assertEachOnce(a, b)
    }

    /**
     * Cobblemon To Item's data format on a plain item: Pokemon items need Cobblemon's registered item, which the
     * bootstrap here lacks. The Pokemon's own data, held item included, is the real save and load.
     */
    private object Packer : PokemonPacker {
        override fun pack(pokemon: Pokemon): ItemStack = ItemStack(Items.PAPER).apply {
            val root = CompoundTag()
            PokemonItemData.putPokemon(root, pokemon.saveToNBT(registries))
            set(DataComponents.CUSTOM_DATA, CustomData.of(root))
        }

        private fun tag(stack: ItemStack) =
            if (stack.`is`(Items.PAPER)) stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.let(PokemonItemData::pokemonTag) else null

        override fun uuid(stack: ItemStack): UUID? = tag(stack)?.getUUID("UUID")
        override fun unpack(stack: ItemStack): Pokemon? = tag(stack)?.let { Pokemon.loadFromNBT(registries, it) }
    }

    companion object {
        private lateinit var registries: RegistryAccess
        private var previousSpecies = emptyMap<ResourceLocation, Species>()

        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
            // Saving a Pokemon reads Cobblemon's level cap and similar limits from its config.
            Cobblemon.config = CobblemonConfig()
            // Reading a Pokemon back resolves its moves and falls back on any registered move; there are none here.
            moves().putIfAbsent(TEST_MOVE, MoveTemplate.dummy(TEST_MOVE))
            ExperienceGroups.registerDefaults()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            previousSpecies = PokemonSpecies.species.associateBy { it.resourceIdentifier }
            val species = Species().apply {
                name = "Noctowl"
                resourceIdentifier = ResourceLocation.parse("cobblemon:noctowl")
                implemented = true
                initialize()
            }
            PokemonSpecies.reload(mapOf(species.resourceIdentifier to species))
        }

        @JvmStatic @AfterAll fun restoreSpecies() {
            PokemonSpecies.reload(previousSpecies)
            moves().remove(TEST_MOVE)
        }

        private const val TEST_MOVE = "presetswitchtest"

        @Suppress("UNCHECKED_CAST")
        private fun moves(): MutableMap<String, MoveTemplate> = Moves::class.java.getDeclaredField("allMoves")
            .apply { isAccessible = true }.get(null) as MutableMap<String, MoveTemplate>
    }
}
