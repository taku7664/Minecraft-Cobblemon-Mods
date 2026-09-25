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
    val forcedDamageRolls: List<NativeForcedDamageRoll> = emptyList(),
) {
    init {
        require(likelihood.isFinite() && likelihood in 0.0..1.0)
        require((status == NativeDamageObservationStatus.CONTRADICTED) == (likelihood == 0.0))
        require(status == NativeDamageObservationStatus.CONSISTENT || explainedPokemonIds.isEmpty())
        require(status == NativeDamageObservationStatus.CONSISTENT || explainedEventSequences.isEmpty())
        require(status == NativeDamageObservationStatus.CONSISTENT || forcedDamageRolls.isEmpty())
        require(forcedDamageRolls.map(NativeForcedDamageRoll::damageCallIndex).distinct().size ==
            forcedDamageRolls.size)
    }
}

/** Conditions a build world only on publicly linked direct damage and native roll support. */
internal object NativeDamageObservationConditioner {
    fun evaluate(
        frame: NativeBattleFrame,
        events: List<BattleObservedEventView>,
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
        val forced = mutableListOf<NativeForcedDamageRoll>()
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
                val loss = roll.possibleHpLosses[index]
                val exactLoss = loss.toDouble() / roll.maxHp.toDouble()
                val publicAfter = NativeShowdownPublicHp.fraction(roll.hpBefore - loss, roll.maxHp)
                val displayedLoss = NativeShowdownPublicHp.fraction(roll.hpBefore, roll.maxHp) - publicAfter
                val exactBeforeToDisplayedAfter = roll.hpBefore.toDouble() / roll.maxHp - publicAfter
                abs(exactLoss - observedFraction) <= FRACTION_EPSILON ||
                    abs(displayedLoss - observedFraction) <= FRACTION_EPSILON ||
                    abs(exactBeforeToDisplayedAfter - observedFraction) <= FRACTION_EPSILON
            }
            if (matchingIndexes.isEmpty()) {
                return NativeDamageObservationConditioning(
                    NativeDamageObservationStatus.CONTRADICTED,
                    likelihood = 0.0,
                )
            }
            likelihood *= matchingIndexes.size.toDouble() / roll.possibleHpLosses.size.toDouble()
            explained += targetId
            explainedSequences += observation.sequence
            forced += NativeForcedDamageRoll(
                damageCallIndex = roll.damageCallIndex,
                percent = 85 + matchingIndexes.first(),
            )
        }
        return NativeDamageObservationConditioning(
            NativeDamageObservationStatus.CONSISTENT,
            likelihood = likelihood,
            explainedPokemonIds = explained,
            explainedEventSequences = explainedSequences,
            forcedDamageRolls = forced,
        )
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private const val FRACTION_EPSILON = 1e-9
}
