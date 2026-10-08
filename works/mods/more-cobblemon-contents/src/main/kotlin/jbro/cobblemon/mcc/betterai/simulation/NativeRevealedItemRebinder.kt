package jbro.cobblemon.mcc.betterai.simulation

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

internal data class NativeItemRebindPlan(
    val definition: NativeBattleDefinition,
    val rebindings: List<NativeItemRebinding>,
)

/**
 * Gives opponents the held item the public battle just revealed when no retained world holds it.
 *
 * A world whose hypothesis named another item is otherwise dropped by the reveal. That is right while some world
 * holds the revealed item, but when none does every world would go and the session with them, although the rest of
 * each hypothesis (spread, moves, ability) is untouched by the reveal. Those worlds keep everything else and carry
 * the revealed item as if held from the start, the way a revealed move is bound into the move set.
 */
internal object NativeRevealedItemRebinder {
    /**
     * The item each Pokemon was first seen holding in [events]. Nothing when an item may have changed hands, since
     * a reveal after Trick or Thief names the item taken, not the one held at the start of the turn.
     */
    fun revealedItems(events: List<BattleObservedEventView>): Map<UUID, String> {
        val transferred = events.any { event ->
            val value = PublicIds.canonical(event.publicValueId.orEmpty())
            (event.kind == BattleObservedEventKind.MOVE_USED && value in TRANSFER_MOVES) ||
                (event.kind == BattleObservedEventKind.ABILITY_REVEALED && value in TRANSFER_ABILITIES)
        }
        if (transferred) return emptyMap()
        val revealed = linkedMapOf<UUID, String>()
        events.forEach { event ->
            if (event.kind != BattleObservedEventKind.HELD_ITEM_REVEALED) return@forEach
            val pokemonId = event.actorPokemonId ?: return@forEach
            val item = PublicIds.canonical(event.publicValueId.orEmpty())
            if (item.isNotEmpty()) revealed.putIfAbsent(pokemonId, item)
        }
        return revealed
    }

    fun holds(definition: NativeBattleDefinition, pokemonId: UUID, item: String): Boolean =
        definition.p2Team.any { UUID.fromString(it.uuid) == pokemonId && PublicIds.canonical(it.item) == item }

    /** Rebinds the opponents in [revealed] whose item in [frame] is still the one their hypothesis started with. */
    fun plan(definition: NativeBattleDefinition, frame: NativeBattleFrame, revealed: Map<UUID, String>): NativeItemRebindPlan {
        if (revealed.isEmpty()) return NativeItemRebindPlan(definition, emptyList())
        val rebindings = mutableListOf<NativeItemRebinding>()
        definition.p2Team.forEach { set ->
            val item = revealed[UUID.fromString(set.uuid)] ?: return@forEach
            val hypothesised = PublicIds.canonical(set.item)
            if (hypothesised == item) return@forEach
            val native = frame.p2Team.singleOrNull { it.uuid == set.uuid } ?: return@forEach
            // An item the world already used or lost has history the reveal cannot rewrite; that world goes.
            if (PublicIds.canonical(native.item) != hypothesised) return@forEach
            rebindings += NativeItemRebinding(set.uuid, hypothesised, item)
        }
        if (rebindings.isEmpty()) return NativeItemRebindPlan(definition, emptyList())
        val replacements = rebindings.associate { it.pokemonUuid to it.replacementItemId }
        return NativeItemRebindPlan(
            definition.copy(p2Team = definition.p2Team.map { set -> replacements[set.uuid]?.let { set.copy(item = it) } ?: set }),
            rebindings,
        )
    }

    /**
     * Takes away the unrevealed items whose effect shows in the public log (Life Orb recoil, Leftovers healing), for
     * a world whose turn the public battle contradicted: when every world named the same such item for an opponent,
     * a turn where it did nothing ends them all, though the rest of each hypothesis may hold.
     */
    fun silence(definition: NativeBattleDefinition, frame: NativeBattleFrame, publicState: BattleStateView): NativeItemRebindPlan {
        val unrevealed = publicState.pokemon.filter { it.side == BattleSide.OPPONENT && it.knownHeldItemId == null }
            .mapTo(hashSetOf()) { it.battlePokemonId.toString() }
        val rebindings = definition.p2Team.mapNotNull { set ->
            val item = PublicIds.canonical(set.item)
            if (set.uuid !in unrevealed || item !in VISIBLE_ITEMS) return@mapNotNull null
            val native = frame.p2Team.singleOrNull { it.uuid == set.uuid } ?: return@mapNotNull null
            if (PublicIds.canonical(native.item) != item) return@mapNotNull null
            NativeItemRebinding(set.uuid, item, "")
        }
        if (rebindings.isEmpty()) return NativeItemRebindPlan(definition, emptyList())
        val silenced = rebindings.mapTo(hashSetOf()) { it.pokemonUuid }
        return NativeItemRebindPlan(
            definition.copy(p2Team = definition.p2Team.map { set -> if (set.uuid in silenced) set.copy(item = "") else set }),
            rebindings,
        )
    }

    /** Items that announce themselves whenever they act, so a turn without them is evidence against them. */
    private val VISIBLE_ITEMS = setOf("lifeorb", "leftovers", "blacksludge", "shellbell", "stickybarb", "rockyhelmet")

    private val TRANSFER_MOVES = setOf("trick", "switcheroo", "thief", "covet", "bestow")
    private val TRANSFER_ABILITIES = setOf("magician", "pickpocket", "symbiosis")
}
