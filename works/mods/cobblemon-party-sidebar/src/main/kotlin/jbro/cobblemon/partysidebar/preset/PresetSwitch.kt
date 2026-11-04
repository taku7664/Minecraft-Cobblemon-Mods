package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
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
 * ([pokemonItems], behind [pokemonStorage]) as items, its own come out of the Pokemon storage, and its borrowed items
 * are lent out again. A Pokemon item the player took out into the inventory is theirs and stays there. The PC is only
 * read, to refuse a Pokemon that is already there.
 */
class PresetSwitch(
    private val party: PartyStore,
    private val pc: Iterable<Pokemon>,
    private val pokemonItems: NonNullList<ItemStack>,
    private val pokemonStorage: StorageContainer,
    private val itemStorage: StorageContainer,
    private val packer: PokemonPacker,
) {
    sealed interface Result {
        /** [moves] lists every item and Pokemon the switch moved, for the support log ([StorageLog]). */
        data class Done(val arriving: List<UUID>, val leaving: List<UUID>, val moves: List<Move> = emptyList()) : Result
        data class Refused(val reason: String) : Result
    }

    /** Where a preset's Pokemon is now: in the party, or as an item in the Pokemon storage. */
    private sealed interface Source {
        val uuid: UUID

        data class Party(val pokemon: Pokemon) : Source {
            override val uuid: UUID get() = pokemon.uuid
        }

        data class Stored(val slot: Int, override val uuid: UUID) : Source
    }

    /** One thing a switch moved: an [action] of `switch_*`, what moved and where from and to. */
    data class Move(val action: String, val what: String, val from: String, val to: String)

    private fun held(pokemon: Pokemon) = "held(pokemon=${pokemon.uuid} species=${pokemon.species.resourceIdentifier})"

    fun current(): List<Pokemon> = (0 until PresetData.PARTY_SIZE).mapNotNull { party.get(it) }

    fun run(target: Preset): Result {
        val current = current()
        val claimed = HashSet<UUID>()
        val sources: List<Source?> = target.slots.map { slot ->
            val id = slot.pokemon?.takeIf(claimed::add) ?: return@map null
            current.firstOrNull { it.uuid == id }?.let { return@map Source.Party(it) }
            pokemonItems.indexOfFirst { packer.uuid(it) == id }.takeIf { it >= 0 }?.let { Source.Stored(it, id) }
        }
        val leaving = current.filter { pokemon -> sources.none { it is Source.Party && it.uuid == pokemon.uuid } }
        val fromStorage = sources.count { it is Source.Stored }
        if (leaving.size > pokemonStorage.freeSlots() + fromStorage) return Result.Refused("pokemon_storage_full")
        if (!itemStorage.canTake(current.map { it.heldItem() }.filter(StoredMark::isStored))) return Result.Refused("storage_full")

        // The arriving Pokemon are read before anything moves, so a bad item stops the switch untouched.
        val arriving = sources.map { source ->
            if (source !is Source.Stored) return@map null
            val pokemon = packer.unpack(pokemonItems[source.slot]) ?: return Result.Refused("failed")
            if (pokemon.uuid != source.uuid) return Result.Refused("failed")
            if (party.any { it.uuid == pokemon.uuid } || pc.any { it.uuid == pokemon.uuid }) return Result.Refused("duplicate")
            pokemon
        }
        // Borrowed items go back first. Packing a Pokemon before that saved the borrowed item inside the Pokemon item
        // while the same item also went back to the storage: one item became two on every round trip.
        val moves = mutableListOf<Move>()
        for (pokemon in current) {
            if (!StoredMark.isStored(pokemon.heldItem())) continue
            val back = pokemon.removeHeldItem()
            if (itemStorage.add(back)) moves += Move("switch_return", StorageLog.describe(back), held(pokemon), "item_storage")
            else pokemon.swapHeldItem(back, false, false)
        }
        // A failure here leaves every Pokemon in the party and every borrowed item in the storage.
        val packed = leaving.map(packer::pack)
        current.forEach { if (it.entity != null) it.recall() }
        current.forEach { party.remove(it) }
        for (source in sources) {
            if (source !is Source.Stored) continue
            pokemonStorage.setItem(source.slot, ItemStack.EMPTY)
        }
        for ((index, stack) in packed.withIndex()) {
            val slot = pokemonItems.indexOfFirst(ItemStack::isEmpty)
            check(slot >= 0) { "Pokemon storage filled up during a switch" }
            pokemonStorage.setItem(slot, stack)
            moves += Move("switch_out", StorageLog.describe(stack), "party(${leaving[index].uuid})", "pokemon_storage[$slot]")
        }
        sources.forEachIndexed { row, source ->
            val pokemon = (source as? Source.Party)?.pokemon ?: arriving[row] ?: return@forEachIndexed
            party.set(PartyPosition(row), pokemon)
            if (source is Source.Stored) moves += Move("switch_in", StorageLog.pokemon(pokemon.uuid,
                pokemon.species.resourceIdentifier.toString()), "pokemon_storage[${source.slot}]", "party[$row]")
        }
        for (row in 0 until PresetData.PARTY_SIZE) {
            val pokemon = party.get(row) ?: continue
            val reference = target.slots[row].reference
            if (reference.isEmpty || !pokemon.heldItem().isEmpty) continue
            val item = itemStorage.takeOne(reference)
            if (item.isEmpty) continue
            pokemon.swapHeldItem(item, false, false)
            if (pokemon.heldItem().isEmpty) itemStorage.add(item)
            else moves += Move("switch_lend", StorageLog.describe(pokemon.heldItem()), "item_storage", held(pokemon))
        }
        // A row whose Pokemon is no longer in the Pokemon storage stays in the preset, red with its silhouette.
        target.slots.forEachIndexed { row, slot -> slot.lost = slot.pokemon != null && sources[row] == null }
        return Result.Done(arriving.filterNotNull().map { it.uuid }, leaving.map { it.uuid }, moves)
    }

    companion object {
        /**
         * Brings [preset] up to date with [party]; true when it changed.
         *
         * Taking a Pokemon out of its row empties the row once it leaves the cursor ([inHand]): that is how a row is
         * cleared. A [PresetSlot.lost] row (its Pokemon went missing while another preset was active) is kept, red
         * with the Pokemon's silhouette, until that Pokemon or another one stands there. Either way a row lets go of a
         * Pokemon now standing in another row.
         */
        fun mirror(preset: Preset, party: PartyStore, inHand: (UUID) -> Boolean): Boolean {
            var changed = false
            for ((row, slot) in preset.slots.withIndex()) {
                val pokemon = party.get(row)
                if (pokemon == null) {
                    val id = slot.pokemon ?: continue
                    if (party.any { it.uuid == id } || (!slot.lost && !inHand(id))) {
                        slot.pokemon = null
                        slot.reference = ItemStack.EMPTY
                        slot.species = ""
                        slot.aspects = emptySet()
                        slot.lost = false
                        changed = true
                    }
                    continue
                }
                if (slot.pokemon != pokemon.uuid) {
                    slot.pokemon = pokemon.uuid
                    slot.reference = ItemStack.EMPTY
                    changed = true
                }
                val species = pokemon.species.resourceIdentifier.toString()
                if (slot.lost || slot.species != species || slot.aspects != pokemon.aspects) {
                    slot.lost = false
                    slot.species = species
                    slot.aspects = pokemon.aspects.toSet()
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
