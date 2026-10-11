package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView

/** Converts a hypothesis-owned native frame into the existing board-evaluation contract. */
internal object NativeBattleStateAdapter {
    fun adapt(frame: NativeBattleFrame, template: BattleStateView, publicTurnOffset: Int = 0): BattleStateView {
        require(publicTurnOffset in 0..1)
        val publicTurn = frame.turn - publicTurnOffset
        val nativeById = (frame.p1Team + frame.p2Team).associateBy { uuid(it.uuid) }
        require(nativeById.keys == template.pokemon.mapTo(linkedSetOf()) { it.battlePokemonId }) {
            "Native hypothesis roster disagrees with the public evaluation roster"
        }
        val pokemon = BattleSide.entries.flatMap { side ->
            fullTeam(side, frame).map { native ->
                val uuid = uuid(native.uuid)
                val source = template.pokemon.single { it.battlePokemonId == uuid }
                adaptPokemon(native, source, side)
            }
        }
        val field = adaptField(frame.field)
        val remaining = BattleSide.entries.associateWith { side ->
            pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        }
        // Every search node is adapted from its parent, and no observation lies past the parent's turn. Deriving
        // keeps the parent's observation and inference lists, the same ones the constructor would copy and check
        // again, so a state's fingerprint can reuse their text by identity instead of reprinting every event.
        if (template.observedEvents.all { it.turn <= publicTurn }) {
            return template.derive(turn = publicTurn, pokemon = pokemon, field = field, remainingPokemonBySide = remaining)
        }
        return BattleStateView(
            battleId = template.battleId,
            format = template.format,
            turn = publicTurn,
            pokemon = pokemon,
            field = field,
            remainingPokemonBySide = remaining,
            observedEvents = template.observedEvents.filter { it.turn <= publicTurn },
            inferences = template.inferences,
        )
    }

    private fun adaptPokemon(
        native: NativePokemonFrame,
        source: BattlePokemonStateView,
        side: BattleSide,
    ): BattlePokemonStateView {
        val fainted = native.hp <= 0
        val nativeSpecies = nativeId(native.species)
        val sourceSpecies = nativeId(source.speciesId)
        return BattlePokemonStateView(
            battlePokemonId = uuid(native.uuid),
            side = side,
            activeSlot = native.activeSlot.takeUnless { fainted },
            speciesId = cobblemonId(nativeSpecies),
            formId = if (nativeSpecies == sourceSpecies) source.formId else nativeSpecies,
            level = native.level,
            hpFraction = (native.hp.toDouble() / native.maxHp.toDouble()).coerceIn(0.0, 1.0),
            statusId = native.status.takeIf(String::isNotBlank),
            statStages = native.boosts.filterValues { it != 0 },
            knownMoveIds = native.moves.mapTo(linkedSetOf()) { cobblemonId(nativeId(it.id)) },
            knownAbilityId = native.ability.takeIf(String::isNotBlank)?.let(::nativeId),
            knownHeldItemId = nativeId(native.item),
            fainted = fainted,
            knownTypeIds = native.types.mapTo(linkedSetOf()) { nativeId(it) },
            combatStats = combatStats(native, side),
            knownFormStates = emptyMap(),
            actionConstraints = BattlePokemonActionConstraintView(
                taunted = "taunt" in native.volatiles,
                trapped = false,
                mustRecharge = "mustrecharge" in native.volatiles,
            ),
            knownVolatileEffectIds = native.volatiles.mapTo(linkedSetOf(), ::nativeId),
            knownBaseStabTypeIds = native.baseStabTypes.mapTo(linkedSetOf(), ::nativeId),
            knownTeraTypeId = native.terastallizedType.takeIf(String::isNotBlank)?.let(::nativeId),
            knownStellarBoostedTypeIds = native.stellarBoostedTypes.mapTo(linkedSetOf(), ::nativeId),
            knownBaseAbilityId = source.knownBaseAbilityId,
        )
    }

    private fun combatStats(
        pokemon: NativePokemonFrame,
        side: BattleSide,
    ): BattleCombatStatRangesView {
        fun stat(id: String): BattleIntegerRange {
            val value = requireNotNull(pokemon.stats[id]) { "Native Pokemon ${pokemon.uuid} is missing stat $id" }
            return BattleIntegerRange(value, value)
        }
        return BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(pokemon.maxHp, pokemon.maxHp),
            attack = stat("atk"),
            defence = stat("def"),
            specialAttack = stat("spa"),
            specialDefence = stat("spd"),
            speed = stat("spe"),
            knowledge = if (side == BattleSide.ALLY) {
                BattleCombatStatKnowledge.EXACT_OWN
            } else {
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE
            },
        )
    }

    private fun adaptField(field: NativeBattleFieldFrame): BattleFieldStateView {
        val room = field.pseudoWeather.filter { nativeId(it.id) in ROOM_EFFECTS }.map(::timedEffect)
        val global = field.pseudoWeather.filterNot { nativeId(it.id) in ROOM_EFFECTS }.map(::timedEffect)
        return BattleFieldStateView(
            weather = field.weather?.let(::timedEffect),
            terrain = field.terrain?.let(::timedEffect),
            roomEffects = room,
            globalEffects = global,
            sideConditions = mapOf(
                BattleSide.ALLY to field.p1SideConditions.map(::timedEffect),
                BattleSide.OPPONENT to field.p2SideConditions.map(::timedEffect),
            ),
        )
    }

    private fun timedEffect(effect: NativeTimedEffectFrame) = BattleTimedEffectView(
        effectId = nativeId(effect.id),
        remainingTurns = effect.remainingTurns,
        stacks = effect.stacks,
    )

    private fun fullTeam(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Team
        BattleSide.OPPONENT -> frame.p2Team
    }

    private fun nativeId(value: String): String = PublicIds.canonical(value)

    /**
     * Every search node adapts all twelve Pokemon; the same few IDs and UUIDs were parsed and spelled out again each
     * time, and each new string hashed again where the state keeps it in a set.
     */
    private const val CACHE_LIMIT = 65_536
    private val prefixedIds = ConcurrentHashMap<String, String>()
    private val uuids = ConcurrentHashMap<String, UUID>()

    private fun cobblemonId(id: String): String = prefixedIds[id] ?: "cobblemon:$id".also {
        if (prefixedIds.size < CACHE_LIMIT) prefixedIds.putIfAbsent(id, it)
    }

    private fun uuid(text: String): UUID = uuids[text] ?: UUID.fromString(text).also {
        if (uuids.size < CACHE_LIMIT) uuids.putIfAbsent(text, it)
    }

    private val ROOM_EFFECTS = setOf("trickroom", "wonderroom", "magicroom")
}
