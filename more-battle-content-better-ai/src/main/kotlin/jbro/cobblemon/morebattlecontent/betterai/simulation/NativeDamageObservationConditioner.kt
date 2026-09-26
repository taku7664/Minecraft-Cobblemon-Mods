package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView

internal enum class NativeDamageObservationStatus {
    NO_APPLICABLE_OBSERVATION,
    INSUFFICIENT_NATIVE_EVIDENCE,
    CONSISTENT,
    CONTRADICTED,
}

internal data class NativeDamageObservationConditioning(
    val status: NativeDamageObservationStatus,
    val likelihood: Double,
    val explainedPokemonIds: Set<UUID> = emptySet(),
    val explainedEventSequences: Set<Long> = emptySet(),
    val forcedDamageRollOptions: List<List<NativeForcedDamageRoll>> = emptyList(),
) {
    init {
        require(likelihood.isFinite() && likelihood in 0.0..1.0)
        require((status == NativeDamageObservationStatus.CONTRADICTED) == (likelihood == 0.0))
        require(status == NativeDamageObservationStatus.CONSISTENT || explainedPokemonIds.isEmpty())
        require(status == NativeDamageObservationStatus.CONSISTENT || explainedEventSequences.isEmpty())
        require(status == NativeDamageObservationStatus.CONSISTENT || forcedDamageRollOptions.isEmpty())
        require(forcedDamageRollOptions.all { it.isNotEmpty() })
        require(forcedDamageRollOptions.map { it.first().damageCallIndex }.distinct().size ==
            forcedDamageRollOptions.size)
        require(forcedDamageRollOptions.all { options ->
            options.all { it.damageCallIndex == options.first().damageCallIndex }
        })
    }
}

/** Conditions a build world only on publicly linked direct damage and native roll support. */
internal object NativeDamageObservationConditioner {
    fun evaluate(
        frame: NativeBattleFrame,
        events: List<BattleObservedEventView>,
        requireActualRollMatch: Boolean = false,
    ): NativeDamageObservationConditioning {
        val observations = events.asSequence()
            .filter { event ->
                event.kind == BattleObservedEventKind.HP_CHANGED &&
                    event.hpFractionDelta?.let { it < 0.0 } == true &&
                    event.precedingActionSequence != null &&
                    event.precedingActionActorPokemonId != null &&
                    !event.precedingActionMoveId.isNullOrBlank() &&
                    event.actorPokemonId != null &&
                    event.publicSourceEffectId == null
            }
            .sortedBy(BattleObservedEventView::sequence)
            .toList()
        if (observations.isEmpty()) {
            return NativeDamageObservationConditioning(
                NativeDamageObservationStatus.NO_APPLICABLE_OBSERVATION,
                likelihood = 1.0,
            )
        }

        val unused = frame.executedDamageRolls.toMutableList()
        var likelihood = 1.0
        val explained = linkedSetOf<UUID>()
        val explainedSequences = linkedSetOf<Long>()
        val forcedOptions = mutableListOf<List<NativeForcedDamageRoll>>()
        observations.forEach { observation ->
            val targetId = requireNotNull(observation.actorPokemonId)
            val attackerId = requireNotNull(observation.precedingActionActorPokemonId)
            val moveId = nativeId(requireNotNull(observation.precedingActionMoveId))
            val index = unused.indexOfFirst { roll ->
                roll.turn == observation.turn &&
                    UUID.fromString(roll.attackerPokemonUuid) == attackerId &&
                    UUID.fromString(roll.targetPokemonUuid) == targetId &&
                    nativeId(roll.moveId) == moveId
            }
            if (index < 0) {
                return NativeDamageObservationConditioning(
                    NativeDamageObservationStatus.INSUFFICIENT_NATIVE_EVIDENCE,
                    likelihood = 1.0,
                )
            }
            val roll = unused.removeAt(index)
            val observedFraction = -requireNotNull(observation.hpFractionDelta)
            val matchingIndexes = roll.possibleHpLosses.indices.filter { index ->
                matchesObservedLoss(roll.hpBefore, roll.maxHp, roll.possibleHpLosses[index], observedFraction)
            }
            if (matchingIndexes.isEmpty() || (requireActualRollMatch &&
                    !matchesObservedLoss(roll.hpBefore, roll.maxHp, roll.actualHpLoss, observedFraction))) {
                return NativeDamageObservationConditioning(
                    NativeDamageObservationStatus.CONTRADICTED,
                    likelihood = 0.0,
                )
            }
            likelihood *= matchingIndexes.size.toDouble() / roll.possibleHpLosses.size.toDouble()
            explained += targetId
            explainedSequences += observation.sequence
            forcedOptions += matchingIndexes.map { matchingIndex ->
                NativeForcedDamageRoll(
                    damageCallIndex = roll.damageCallIndex,
                    percent = 85 + matchingIndex,
                )
            }
        }
        return NativeDamageObservationConditioning(
            NativeDamageObservationStatus.CONSISTENT,
            likelihood = likelihood,
            explainedPokemonIds = explained,
            explainedEventSequences = explainedSequences,
            forcedDamageRollOptions = forcedOptions,
        )
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun matchesObservedLoss(hpBefore: Int, maxHp: Int, loss: Int, observedFraction: Double): Boolean {
        val exactLoss = loss.toDouble() / maxHp.toDouble()
        val publicAfter = NativeShowdownPublicHp.fraction(hpBefore - loss, maxHp)
        val displayedLoss = NativeShowdownPublicHp.fraction(hpBefore, maxHp) - publicAfter
        val exactBeforeToDisplayedAfter = hpBefore.toDouble() / maxHp - publicAfter
        return abs(exactLoss - observedFraction) <= FRACTION_EPSILON ||
            abs(displayedLoss - observedFraction) <= FRACTION_EPSILON ||
            abs(exactBeforeToDisplayedAfter - observedFraction) <= FRACTION_EPSILON
    }

    private const val FRACTION_EPSILON = 1e-9
}
