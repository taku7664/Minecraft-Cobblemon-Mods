package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFlagAbilityImmunityTest {
    @Test
    fun `wind rider blocks wind moves but not ordinary flying moves`() {
        assertTrue(project("heatwave", "fire", "windrider", setOf("wind")).publiclyNullified)
        assertFalse(project("bravebird", "flying", "windrider").publiclyNullified)
    }

    @Test
    fun `bulletproof and soundproof use move flags rather than move type`() {
        assertTrue(project("shadowball", "ghost", "bulletproof", setOf("bullet")).publiclyNullified)
        assertTrue(project("boomburst", "normal", "soundproof", setOf("sound")).publiclyNullified)
        assertFalse(project("tackle", "normal", "soundproof").publiclyNullified)
    }

    @Test
    fun `good as gold blocks hostile status and magic bounce recognizes reflectable moves`() {
        assertTrue(projectStatus("thunderwave", "goodasgold").publiclyNullified)
        assertTrue(projectStatus("thunderwave", "magicbounce", setOf("reflectable")).publiclyNullified)
    }

    private fun project(moveId: String, type: String, defenderAbility: String, flags: Set<String> = emptySet()) =
        LocalPublicMechanicsKernel.projectMove(
            move(moveId, type, flags),
            context(defenderAbility, move(moveId, type, flags)),
        )

    private fun projectStatus(moveId: String, defenderAbility: String, flags: Set<String> = emptySet()) =
        move(moveId, "electric", flags, BattleMoveDamageCategory.STATUS).let { candidate ->
            LocalPublicMechanicsKernel.projectMove(candidate, context(defenderAbility, candidate))
        }

    private fun context(ability: String, candidate: BattleActionCandidate): BattleDecisionContext {
        val actor = mon(BattleSide.ALLY, null)
        val target = mon(BattleSide.OPPONENT, ability)
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(actor, target), BattleFieldStateView.empty(),
                BattleSide.entries.associateWith { 1 }, emptyList(), emptyList(),
            ),
            candidates = listOf(candidate), deadlineEpochMillis = Long.MAX_VALUE,
        )
    }

    private fun move(
        id: String,
        type: String,
        flags: Set<String>,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = id, targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            type, category, if (category == BattleMoveDamageCategory.STATUS) 0.0 else 90.0, 100.0, 0, 10,
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = false,
                mechanicFlags = flags,
            ),
        ),
    )

    private fun mon(side: BattleSide, ability: String?) = BattlePokemonStateView(
        UUID.randomUUID(), side, 0, "probe", null, 50, 1.0, null, emptyMap(), emptySet(), ability, null,
        false, setOf("normal"), if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(140, 160), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                BattleIntegerRange(90, 110), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )
}
