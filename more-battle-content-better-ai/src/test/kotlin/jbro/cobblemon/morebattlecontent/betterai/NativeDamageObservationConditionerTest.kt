package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageObservationConditioner
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageObservationStatus
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeDamageRollFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownPublicHp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeDamageObservationConditionerTest {
    @Test
    fun `public direct damage keeps all rolls consistent with its HP display`() {
        val possible = (40..55).toList()
        val result = NativeDamageObservationConditioner.evaluate(
            frame(possible),
            listOf(damageEvent(delta = -0.25)),
        )

        assertEquals(NativeDamageObservationStatus.CONSISTENT, result.status)
        assertEquals(2.0 / 16.0, result.likelihood, 1e-12)
        assertEquals(setOf(TARGET), result.explainedPokemonIds)
        assertEquals(setOf(3L), result.explainedEventSequences)
        assertEquals(1, result.forcedDamageRolls.size)
        assertEquals(95, result.forcedDamageRolls.single().percent)
    }

    @Test
    fun `rounded public HP damage retains every native roll with the same displayed loss`() {
        // Showdown displays 190/202 as 95/100. Both 47 and 48 damage display 71/100,
        // so the public event reports a 24-point change even though neither exact loss is 24%.
        val result = NativeDamageObservationConditioner.evaluate(
            frame((40..55).toList()).copy(executedDamageRolls = listOf(
                NativeDamageRollFrame(
                    turn = 1,
                    attackerPokemonUuid = ATTACKER.toString(),
                    targetPokemonUuid = TARGET.toString(),
                    moveId = "tackle",
                    hpBefore = 190,
                    maxHp = 202,
                    actualHpLoss = 48,
                    possibleHpLosses = (40..55).toList(),
                ),
            )),
            listOf(damageEvent(delta = -0.24)),
        )

        assertEquals(NativeDamageObservationStatus.CONSISTENT, result.status)
        assertEquals(2.0 / 16.0, result.likelihood, 1e-12)
        assertEquals(92, result.forcedDamageRolls.single().percent)
    }

    @Test
    fun `first public HP event can subtract a displayed value from an exact seeded HP`() {
        val roll = NativeDamageRollFrame(
            turn = 1,
            attackerPokemonUuid = ATTACKER.toString(),
            targetPokemonUuid = TARGET.toString(),
            moveId = "tackle",
            hpBefore = 190,
            maxHp = 202,
            actualHpLoss = 48,
            possibleHpLosses = (40..55).toList(),
        )
        val delta = NativeShowdownPublicHp.fraction(142, 202) - 190.0 / 202.0

        val result = NativeDamageObservationConditioner.evaluate(
            frame((40..55).toList()).copy(executedDamageRolls = listOf(roll)),
            listOf(damageEvent(delta)),
        )

        assertEquals(NativeDamageObservationStatus.CONSISTENT, result.status)
        assertEquals(2.0 / 16.0, result.likelihood, 1e-12)
    }

    @Test
    fun `wounded HP never displays as full in the bundled Showdown percentage protocol`() {
        assertEquals(0.99, NativeShowdownPublicHp.fraction(201, 202), 1e-12)
        assertEquals(1.0, NativeShowdownPublicHp.fraction(202, 202), 1e-12)
        assertEquals(0.01, NativeShowdownPublicHp.fraction(1, 202), 1e-12)
        assertEquals(0.0, NativeShowdownPublicHp.fraction(0, 202), 1e-12)
    }

    @Test
    fun `impossible public damage contradicts the build world but missing native evidence does not`() {
        val contradicted = NativeDamageObservationConditioner.evaluate(
            frame((40..55).toList()),
            listOf(damageEvent(delta = -0.10)),
        )
        val insufficient = NativeDamageObservationConditioner.evaluate(
            frame(emptyList()),
            listOf(damageEvent(delta = -0.25)),
        )

        assertEquals(NativeDamageObservationStatus.CONTRADICTED, contradicted.status)
        assertEquals(0.0, contradicted.likelihood)
        assertEquals(NativeDamageObservationStatus.INSUFFICIENT_NATIVE_EVIDENCE, insufficient.status)
        assertEquals(1.0, insufficient.likelihood)
        assertTrue(insufficient.explainedPokemonIds.isEmpty())
    }

    @Test
    fun `residual damage without a public action link never changes a build posterior`() {
        val residual = BattleObservedEventView(
            sequence = 3,
            turn = 1,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = TARGET,
            hpFractionDelta = -0.0625,
            publicSourceEffectId = "brn",
        )

        val result = NativeDamageObservationConditioner.evaluate(frame((40..55).toList()), listOf(residual))

        assertEquals(NativeDamageObservationStatus.NO_APPLICABLE_OBSERVATION, result.status)
        assertEquals(1.0, result.likelihood)
    }

    private fun frame(possible: List<Int>) = NativeBattleFrame(
        snapshotJson = "root",
        turn = 2,
        requestState = "move",
        ended = false,
        p1Active = emptyList(),
        p2Active = emptyList(),
        p1Team = emptyList(),
        p2Team = emptyList(),
        p1RequestJson = "null",
        p2RequestJson = "null",
        log = emptyList(),
        executedDamageRolls = if (possible.isEmpty()) emptyList() else listOf(
            NativeDamageRollFrame(
                turn = 1,
                attackerPokemonUuid = ATTACKER.toString(),
                targetPokemonUuid = TARGET.toString(),
                moveId = "tackle",
                hpBefore = 200,
                maxHp = 200,
                actualHpLoss = 48,
                possibleHpLosses = possible,
            ),
        ),
    )

    private fun damageEvent(delta: Double) = BattleObservedEventView(
        sequence = 3,
        turn = 1,
        kind = BattleObservedEventKind.HP_CHANGED,
        actorPokemonId = TARGET,
        hpFractionDelta = delta,
        precedingActionSequence = 2,
        precedingActionActorPokemonId = ATTACKER,
        precedingActionMoveId = "tackle",
    )

    private companion object {
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000501")
        val TARGET: UUID = UUID.fromString("00000000-0000-0000-0000-000000000502")
    }
}
