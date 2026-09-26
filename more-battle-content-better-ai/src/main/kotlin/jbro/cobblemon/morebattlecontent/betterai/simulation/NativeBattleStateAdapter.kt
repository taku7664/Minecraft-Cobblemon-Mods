package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView

/** Converts a hypothesis-owned native frame into the existing board-evaluation contract. */
internal object NativeBattleStateAdapter {
    fun adapt(frame: NativeBattleFrame, template: BattleStateView, publicTurnOffset: Int = 0): BattleStateView {
        require(publicTurnOffset in 0..1)
        val publicTurn = frame.turn - publicTurnOffset
        val nativeById = (frame.p1Team + frame.p2Team).associateBy { UUID.fromString(it.uuid) }
        require(nativeById.keys == template.pokemon.mapTo(linkedSetOf()) { it.battlePokemonId }) {
            "Native hypothesis roster disagrees with the public evaluation roster"
        }
        val pokemon = BattleSide.entries.flatMap { side ->
            fullTeam(side, frame).map { native ->
                val uuid = UUID.fromString(native.uuid)
                val source = template.pokemon.single { it.battlePokemonId == uuid }
                adaptPokemon(native, source, side)
            }
        }
        return BattleStateView(
            battleId = template.battleId,
            format = template.format,
            turn = publicTurn,
            pokemon = pokemon,
            field = adaptField(frame.field),
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
            },
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
            battlePokemonId = UUID.fromString(native.uuid),
            side = side,
            activeSlot = native.activeSlot.takeUnless { fainted },
            speciesId = "cobblemon:$nativeSpecies",
            formId = if (nativeSpecies == sourceSpecies) source.formId else nativeSpecies,
            level = native.level,
            hpFraction = (native.hp.toDouble() / native.maxHp.toDouble()).coerceIn(0.0, 1.0),
            statusId = native.status.takeIf(String::isNotBlank),
            statStages = native.boosts.filterValues { it != 0 },
            knownMoveIds = native.moves.mapTo(linkedSetOf()) { "cobblemon:${nativeId(it.id)}" },
            knownAbilityId = native.ability.takeIf(String::isNotBlank)?.let(::nativeId),
            knownHeldItemId = native.item.takeIf(String::isNotBlank)?.let(::nativeId),
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

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private val ROOM_EFFECTS = setOf("trickroom", "wonderroom", "magicroom")
}
