package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** How a Pokemon becomes an item and back: Cobblemon To Item's format in the game, a stand-in in tests. */
interface PokemonPacker {
    fun pack(pokemon: Pokemon): ItemStack
    fun uuid(stack: ItemStack): UUID?
    fun unpack(stack: ItemStack): Pokemon?

    /** Cobblemon To Item's Pokemon items. */
    class Items(private val registries: RegistryAccess) : PokemonPacker {
        override fun pack(pokemon: Pokemon): ItemStack {
            val stack = PokemonItemData.create(pokemon, registries)
            check(unpack(stack)?.uuid == pokemon.uuid) { "Pokemon item changed identity" }
            return stack
        }

        override fun uuid(stack: ItemStack): UUID? = PokemonItemData.pokemonTag(stack)?.getUUID("UUID")

        override fun unpack(stack: ItemStack): Pokemon? =
            PokemonItemData.pokemonTag(stack)?.let { Pokemon.loadFromNBT(registries, it) }
    }
}

/**
 * The moving part of a preset switch, apart from the player so a test can run it on real Cobblemon stores.
 *
 * The party's borrowed items go back to [itemStorage], the Pokemon [target] does not use go into the Pokemon storage
 * ([pokemonItems], behind [pokemonStorage]) as items, its own come out of the Pokemon storage or [inventory], and its
 * borrowed items are lent out again. The PC is only read, to refuse a Pokemon that is already there.
 */
class PresetSwitch(
    private val party: PartyStore,
    private val pc: Iterable<Pokemon>,
    private val pokemonItems: NonNullList<ItemStack>,
    private val pokemonStorage: StorageContainer,
    private val itemStorage: StorageContainer,
    private val inventory: Container,
    private val packer: PokemonPacker,
) {
    sealed interface Result {
        data class Done(val arriving: List<UUID>, val leaving: List<UUID>) : Result
        data class Refused(val reason: String) : Result
    }

    /** Where a preset's Pokemon is now: in the party, or as an item in the Pokemon storage or player inventory. */
    private sealed interface Source {
        val uuid: UUID

        data class Party(val pokemon: Pokemon) : Source {
            override val uuid: UUID get() = pokemon.uuid
        }

        data class Stored(val slot: Int, override val uuid: UUID, val inventory: Boolean) : Source
    }

    fun current(): List<Pokemon> = (0 until PresetData.PARTY_SIZE).mapNotNull { party.get(it) }

    fun run(target: Preset): Result {
        val current = current()
        val claimed = HashSet<UUID>()
        val sources: List<Source?> = target.slots.map { slot ->
            val id = slot.pokemon?.takeIf(claimed::add) ?: return@map null
            current.firstOrNull { it.uuid == id }?.let { return@map Source.Party(it) }
            pokemonItems.indexOfFirst { packer.uuid(it) == id }.takeIf { it >= 0 }?.let { return@map Source.Stored(it, id, false) }
            (0 until inventory.containerSize).firstOrNull { packer.uuid(inventory.getItem(it)) == id }
                ?.let { Source.Stored(it, id, true) }
        }
        val leaving = current.filter { pokemon -> sources.none { it is Source.Party && it.uuid == pokemon.uuid } }
        val fromStorage = sources.count { it is Source.Stored && !it.inventory }
        if (leaving.size > pokemonStorage.freeSlots() + fromStorage) return Result.Refused("pokemon_storage_full")
        if (!itemStorage.canTake(current.map { it.heldItem() }.filter(StoredMark::isStored))) return Result.Refused("storage_full")

        // The arriving Pokemon are read before anything moves, so a bad item stops the switch untouched.
        val arriving = sources.map { source ->
            if (source !is Source.Stored) return@map null
            val stack = if (source.inventory) inventory.getItem(source.slot) else pokemonItems[source.slot]
            val pokemon = packer.unpack(stack) ?: return Result.Refused("failed")
            if (pokemon.uuid != source.uuid) return Result.Refused("failed")
            if (party.any { it.uuid == pokemon.uuid } || pc.any { it.uuid == pokemon.uuid }) return Result.Refused("duplicate")
            pokemon
        }
        // Borrowed items go back first. Packing a Pokemon before that saved the borrowed item inside the Pokemon item
        // while the same item also went back to the storage: one item became two on every round trip.
        for (pokemon in current) {
            if (!StoredMark.isStored(pokemon.heldItem())) continue
            val back = pokemon.removeHeldItem()
            if (!itemStorage.add(back)) pokemon.swapHeldItem(back, false, false)
        }
        // A failure here leaves every Pokemon in the party and every borrowed item in the storage.
        val packed = leaving.map(packer::pack)
        current.forEach { if (it.entity != null) it.recall() }
        current.forEach { party.remove(it) }
        for (source in sources) {
            if (source !is Source.Stored) continue
            if (source.inventory) inventory.setItem(source.slot, ItemStack.EMPTY) else pokemonStorage.setItem(source.slot, ItemStack.EMPTY)
        }
        for (stack in packed) {
            val slot = pokemonItems.indexOfFirst(ItemStack::isEmpty)
            check(slot >= 0) { "Pokemon storage filled up during a switch" }
            pokemonStorage.setItem(slot, stack)
        }
        sources.forEachIndexed { row, source ->
            val pokemon = (source as? Source.Party)?.pokemon ?: arriving[row] ?: return@forEachIndexed
            party.set(PartyPosition(row), pokemon)
        }
        for (row in 0 until PresetData.PARTY_SIZE) {
            val pokemon = party.get(row) ?: continue
            val reference = target.slots[row].reference
            if (reference.isEmpty || !pokemon.heldItem().isEmpty) continue
            val item = itemStorage.takeOne(reference)
            if (item.isEmpty) continue
            pokemon.swapHeldItem(item, false, false)
            if (pokemon.heldItem().isEmpty) itemStorage.add(item)
        }
        inventory.setChanged()
        return Result.Done(arriving.filterNotNull().map { it.uuid }, leaving.map { it.uuid })
    }

    companion object {
        /**
         * Brings [preset] up to date with [party]; true when it changed. A row left empty keeps its Pokemon while
         * [putAway] says that Pokemon is not in another row, the Pokemon storage or the PC (it is on the cursor or in
         * the inventory), so another preset's switch can still find it.
         */
        fun mirror(preset: Preset, party: PartyStore, putAway: (UUID) -> Boolean): Boolean {
            var changed = false
            for ((row, slot) in preset.slots.withIndex()) {
                val pokemon = party.get(row)
                if (pokemon == null) {
                    val id = slot.pokemon ?: continue
                    if (putAway(id)) {
                        slot.pokemon = null
                        slot.reference = ItemStack.EMPTY
                        changed = true
                    }
                    continue
                }
                if (slot.pokemon != pokemon.uuid) {
                    slot.pokemon = pokemon.uuid
                    slot.reference = ItemStack.EMPTY
                    changed = true
                }
                val held = pokemon.heldItem()
                if (StoredMark.isStored(held)) {
                    if (!StoredMark.sameItem(slot.reference, held)) {
                        slot.reference = StoredMark.prototype(held)
                        changed = true
                    }
                } else if (!held.isEmpty && !slot.reference.isEmpty) {
                    slot.reference = ItemStack.EMPTY
                    changed = true
                }
                // Holding nothing keeps the reference: the item was used up or taken, and the row shows it missing.
            }
            return changed
        }
    }
}
