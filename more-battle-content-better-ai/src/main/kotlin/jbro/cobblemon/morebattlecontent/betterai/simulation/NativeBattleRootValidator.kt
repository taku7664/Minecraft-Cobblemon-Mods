package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import kotlin.math.abs

internal enum class NativeBattleRootIssueCode {
    MALFORMED_FRAME,
    FORMAT_MISMATCH,
    DEFINITION_FRAME_MISMATCH,
    OPENING_STATE_MISMATCH,
    ACTIVE_VIEW_MISMATCH,
    SIDE_MISMATCH,
    TURN_MISMATCH,
    ACTIVE_SLOT_MISMATCH,
    LEVEL_MISMATCH,
    HP_MISMATCH,
    STATUS_MISMATCH,
    STAT_STAGES_MISMATCH,
    VOLATILE_MISMATCH,
    TYPE_MISMATCH,
    ABILITY_MISMATCH,
    ITEM_MISMATCH,
    MOVESET_MISMATCH,
    COMBAT_STATS_MISMATCH,
    REMAINING_POKEMON_MISMATCH,
    FIELD_MISMATCH,
}

internal data class NativeBattleRootIssue(
    val code: NativeBattleRootIssueCode,
    val battlePokemonId: UUID? = null,
)

/** Rejects a synthetic native root that contradicts its definition or any supplied public fact. */
internal object NativeBattleRootValidator {
    fun validate(
        definition: NativeBattleDefinition,
        frame: NativeBattleFrame,
        publicState: BattleStateView,
    ): List<NativeBattleRootIssue> {
        val issues = linkedSetOf<NativeBattleRootIssue>()
        val publicFormatId = when (publicState.format) {
            BattleFormat.SINGLE -> "cobblemonsingles"
            BattleFormat.DOUBLE -> "cobblemondoubles"
        }
        if (normalizedId(definition.formatId) != publicFormatId) {
            issue(issues, NativeBattleRootIssueCode.FORMAT_MISMATCH)
        }
        val definitionSides = mapOf(
            BattleSide.ALLY to definition.p1Team,
            BattleSide.OPPONENT to definition.p2Team,
        )
        val frameSides = mapOf(
            BattleSide.ALLY to frame.p1Team,
            BattleSide.OPPONENT to frame.p2Team,
        )
        val activeSides = mapOf(
            BattleSide.ALLY to frame.p1Active,
            BattleSide.OPPONENT to frame.p2Active,
        )
        definitionSides.forEach { (side, sets) ->
            val native = frameSides.getValue(side)
            val nativeById = native.associateBy(NativePokemonFrame::uuid)
            if (nativeById.size != native.size || sets.map(NativePokemonSet::uuid).toSet() != nativeById.keys) {
                issue(issues, NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH)
            }
            sets.forEach { set ->
                val actual = nativeById[set.uuid] ?: return@forEach
                val sourceSet = actual.sourceSet
                if (sourceSet == null ||
                    normalizedId(set.species) != normalizedId(sourceSet.species) ||
                    normalizedId(set.ability) != normalizedId(sourceSet.ability) ||
                    normalizedId(set.item) != normalizedId(sourceSet.item) ||
                    normalizedId(set.nature) != normalizedId(sourceSet.nature) ||
                    set.gender != sourceSet.gender ||
                    normalizedId(set.teraType.orEmpty()) != normalizedId(sourceSet.teraType) ||
                    set.evs != sourceSet.evs ||
                    set.ivs != sourceSet.ivs ||
                    set.level != actual.level ||
                    set.moves.map(::normalizedId) != sourceSet.moves.map(::normalizedId)
                ) {
                    issue(
                        issues,
                        NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH,
                        uuidOrNull(set.uuid),
                    )
                }
            }
            val expectedActive = native.filter { it.activeSlot != null }.sortedBy { it.activeSlot }
            val actualActive = activeSides.getValue(side)
            if (actualActive.map(NativePokemonFrame::activeSlot) != actualActive.indices.toList() ||
                actualActive != expectedActive
            ) {
                issue(issues, NativeBattleRootIssueCode.ACTIVE_VIEW_MISMATCH)
            }
        }
        definition.openingState?.pokemon?.forEach { opening ->
            val actual = frameSides.values.asSequence().flatten().firstOrNull { it.uuid == opening.uuid }
            val sourceSet = actual?.sourceSet
            if (sourceSet == null ||
                sourceSet.openingHp != opening.hp ||
                sourceSet.openingMaxHp != opening.maxHp ||
                statusId(sourceSet.openingStatus) != statusId(opening.status) ||
                normalizedId(sourceSet.ability) != normalizedId(opening.ability) ||
                normalizedId(sourceSet.item) != normalizedId(opening.item)
            ) {
                issue(
                    issues,
                    NativeBattleRootIssueCode.OPENING_STATE_MISMATCH,
                    uuidOrNull(opening.uuid),
                )
            }
        }

        val adapted = try {
            NativeBattleStateAdapter.adapt(frame, publicState)
        } catch (_: RuntimeException) {
            issue(issues, NativeBattleRootIssueCode.MALFORMED_FRAME)
            return issues.toList()
        }
        if (adapted.turn != publicState.turn) issue(issues, NativeBattleRootIssueCode.TURN_MISMATCH)
        val adaptedById = adapted.pokemon.associateBy(BattlePokemonStateView::battlePokemonId)
        publicState.pokemon.forEach { public ->
            val actual = adaptedById[public.battlePokemonId] ?: return@forEach
            val id = public.battlePokemonId
            if (actual.side != public.side) issue(issues, NativeBattleRootIssueCode.SIDE_MISMATCH, id)
            if (actual.activeSlot != public.activeSlot) issue(issues, NativeBattleRootIssueCode.ACTIVE_SLOT_MISMATCH, id)
            if (actual.level != public.level) issue(issues, NativeBattleRootIssueCode.LEVEL_MISMATCH, id)
            val exactHpMatches = abs(actual.hpFraction - public.hpFraction) <= FRACTION_EPSILON
            val publicOpponentHpMatches = public.side == BattleSide.OPPONENT &&
                frame.p2Team.firstOrNull { it.uuid == id.toString() }?.let { native ->
                    abs(NativeShowdownPublicHp.fraction(native.hp, native.maxHp) - public.hpFraction) <=
                        FRACTION_EPSILON
                } == true
            if ((!exactHpMatches && !publicOpponentHpMatches) || actual.fainted != public.fainted) {
                issue(issues, NativeBattleRootIssueCode.HP_MISMATCH, id)
            }
            if (statusId(actual.statusId) != statusId(public.statusId)) {
                issue(issues, NativeBattleRootIssueCode.STATUS_MISMATCH, id)
            }
            if (normalizedStages(actual.statStages) != normalizedStages(public.statStages)) {
                issue(issues, NativeBattleRootIssueCode.STAT_STAGES_MISMATCH, id)
            }
            val actualVolatiles = actual.knownVolatileEffectIds.mapTo(linkedSetOf(), ::normalizedId)
            val publicVolatiles = public.knownVolatileEffectIds.mapTo(linkedSetOf(), ::normalizedId)
            if (!actualVolatiles.containsAll(publicVolatiles) ||
                public.actionConstraints.taunted != actual.actionConstraints.taunted ||
                public.actionConstraints.mustRecharge != actual.actionConstraints.mustRecharge ||
                public.actionConstraints.trapped != actual.actionConstraints.trapped ||
                public.actionConstraints.encoreMoveId?.let(::normalizedId) !=
                actual.actionConstraints.encoreMoveId?.let(::normalizedId)
            ) {
                issue(issues, NativeBattleRootIssueCode.VOLATILE_MISMATCH, id)
            }
            val publicTypes = public.knownTypeIds.mapTo(linkedSetOf(), ::normalizedId)
            val actualTypes = actual.knownTypeIds.mapTo(linkedSetOf(), ::normalizedId)
            if (publicTypes.isNotEmpty() && publicTypes != actualTypes) {
                issue(issues, NativeBattleRootIssueCode.TYPE_MISMATCH, id)
            }
            public.knownAbilityId?.let { known ->
                if (normalizedId(known) != normalizedId(actual.knownAbilityId.orEmpty())) {
                    issue(issues, NativeBattleRootIssueCode.ABILITY_MISMATCH, id)
                }
            }
            val publicItem = public.knownHeldItemId?.let(::normalizedId)
            val actualItem = actual.knownHeldItemId?.let(::normalizedId)
            val itemMismatch = if (public.side == BattleSide.ALLY) {
                publicItem != actualItem
            } else {
                publicItem != null && publicItem != actualItem
            }
            if (itemMismatch) issue(issues, NativeBattleRootIssueCode.ITEM_MISMATCH, id)
            val publicMoves = public.knownMoveIds.mapTo(linkedSetOf(), ::normalizedId)
            val actualMoves = actual.knownMoveIds.mapTo(linkedSetOf(), ::normalizedId)
            if (!actualMoves.containsAll(publicMoves)) issue(issues, NativeBattleRootIssueCode.MOVESET_MISMATCH, id)
            public.combatStats?.let { expected ->
                val actualStats = actual.combatStats
                if (actualStats == null || !statsCompatible(expected, actualStats)) {
                    issue(issues, NativeBattleRootIssueCode.COMBAT_STATS_MISMATCH, id)
                }
            }
        }
        if (adapted.remainingPokemonBySide != publicState.remainingPokemonBySide) {
            issue(issues, NativeBattleRootIssueCode.REMAINING_POKEMON_MISMATCH)
        }
        if (!fieldCompatible(publicState.field, adapted.field)) {
            issue(issues, NativeBattleRootIssueCode.FIELD_MISMATCH)
        }
        return issues.toList()
    }

    private fun statsCompatible(
        expected: BattleCombatStatRangesView,
        actual: BattleCombatStatRangesView,
    ): Boolean {
        fun compatible(range: BattleIntegerRange, value: BattleIntegerRange): Boolean =
            value.minimum == value.maximum && value.minimum in range.minimum..range.maximum
        val all = compatible(expected.maxHp, actual.maxHp) &&
            compatible(expected.attack, actual.attack) &&
            compatible(expected.defence, actual.defence) &&
            compatible(expected.specialAttack, actual.specialAttack) &&
            compatible(expected.specialDefence, actual.specialDefence) &&
            compatible(expected.speed, actual.speed)
        return all && (expected.knowledge != BattleCombatStatKnowledge.EXACT_OWN ||
            listOf(
                expected.maxHp,
                expected.attack,
                expected.defence,
                expected.specialAttack,
                expected.specialDefence,
                expected.speed,
            ).all { it.minimum == it.maximum })
    }

    private fun fieldCompatible(expected: BattleFieldStateView, actual: BattleFieldStateView): Boolean =
        effectCompatible(expected.weather, actual.weather) &&
            effectCompatible(expected.terrain, actual.terrain) &&
            effectsCompatible(expected.roomEffects, actual.roomEffects) &&
            effectsCompatible(expected.globalEffects, actual.globalEffects) &&
            BattleSide.entries.all { side ->
                effectsCompatible(
                    expected.sideConditions.getValue(side),
                    actual.sideConditions.getValue(side),
                )
            }

    private fun effectsCompatible(
        expected: List<BattleTimedEffectView>,
        actual: List<BattleTimedEffectView>,
    ): Boolean {
        val actualById = actual.associateBy { normalizedId(it.effectId) }
        return expected.size == actual.size && expected.all { effect ->
            effectCompatible(effect, actualById[normalizedId(effect.effectId)])
        }
    }

    private fun effectCompatible(expected: BattleTimedEffectView?, actual: BattleTimedEffectView?): Boolean {
        if (expected == null || actual == null) return expected == null && actual == null
        if (normalizedId(expected.effectId) != normalizedId(actual.effectId) || expected.stacks != actual.stacks) {
            return false
        }
        val expectedRange = expected.remainingTurnsRange
        return when {
            expected.remainingTurns != null -> expected.remainingTurns == actual.remainingTurns
            expectedRange != null -> actual.remainingTurns != null &&
                actual.remainingTurns in expectedRange.minimum..expectedRange.maximum
            else -> true
        }
    }

    private fun normalizedStages(stages: Map<String, Int>): Map<String, Int> = stages.entries.associate { (id, value) ->
        val normalized = when (normalizedId(id)) {
            "attack", "atk" -> "atk"
            "defence", "defense", "def" -> "def"
            "specialattack", "spatk", "spa" -> "spa"
            "specialdefence", "specialdefense", "spdef", "spd" -> "spd"
            "speed", "spe" -> "spe"
            "accuracy" -> "accuracy"
            "evasion" -> "evasion"
            else -> normalizedId(id)
        }
        normalized to value
    }.filterValues { it != 0 }

    private fun statusId(value: String?): String? = when (normalizedId(value.orEmpty())) {
        "", "none" -> null
        "burn", "brn" -> "brn"
        "paralysis", "paralyzed", "par" -> "par"
        "poison", "psn" -> "psn"
        "badlypoisoned", "toxic", "tox" -> "tox"
        "sleep", "asleep", "slp" -> "slp"
        "freeze", "frozen", "frz" -> "frz"
        else -> normalizedId(value.orEmpty())
    }

    private fun normalizedId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun issue(
        issues: MutableSet<NativeBattleRootIssue>,
        code: NativeBattleRootIssueCode,
        pokemonId: UUID? = null,
    ) {
        issues += NativeBattleRootIssue(code, pokemonId)
    }

    private const val FRACTION_EPSILON = 1e-9
}
