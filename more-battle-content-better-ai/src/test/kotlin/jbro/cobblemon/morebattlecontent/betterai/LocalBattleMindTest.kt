package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleMind
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalPositionRiskBudget
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalTrainerStyleModel
import jbro.cobblemon.morebattlecontent.betterai.policy.forPlanOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBattleMindTest {
    @Test
    fun `trainer identity dominates style while each match keeps a small mood variation`() {
        val trainer = "mbc:ace_trainer_mina"
        val first = LocalTrainerStyleModel.derive(
            trainer,
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
        )
        val second = LocalTrainerStyleModel.derive(
            trainer,
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
        )

        assertNotEquals(first, second)
        assertTrue(kotlin.math.abs(first.riskOffset - second.riskOffset) <= 0.04)
        assertTrue(kotlin.math.abs(first.mixupDisposition - second.mixupDisposition) <= 0.20)
    }

    @Test
    fun `risk budget rises when behind and falls when ahead`() {
        val behind = LocalPositionRiskBudget.resolve(0.5, positionAdvantage = -0.8, styleRiskOffset = 0.0)
        val neutral = LocalPositionRiskBudget.resolve(0.5, positionAdvantage = 0.0, styleRiskOffset = 0.0)
        val ahead = LocalPositionRiskBudget.resolve(0.5, positionAdvantage = 0.8, styleRiskOffset = 0.0)

        assertTrue(behind > neutral)
        assertTrue(neutral > ahead)
    }

    @Test
    fun `plan owner filter hides only the foreign plan and preserves public memory`() {
        val memory = BattleTacticalMemoryView(
            activePlan = BattlePlanView(BattlePlanIntent.CREATE_SAFE_ENTRY, expiresAtTurn = 5),
            activePlanOwner = BattlePlanOwner.LOCAL_BRAIN,
            nonProgressControlStreak = 3,
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.SINGLE,
                turn = 2,
                pokemon = emptyList(),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = memory,
        )

        assertSame(context, context.forPlanOwner(BattlePlanOwner.LOCAL_BRAIN))
        val routerContext = context.forPlanOwner(BattlePlanOwner.PRIMARY_BRAIN)
        assertNull(routerContext.memory.activePlan)
        assertNull(routerContext.memory.activePlanOwner)
        assertEquals(3, routerContext.memory.nonProgressControlStreak)
    }

    @Test
    fun `unrevealed living opponents are conservatively counted at full health in position risk`() {
        val battleId = UUID.randomUUID()
        fun pokemon(
            side: BattleSide,
            hp: Double,
            fainted: Boolean,
            activeSlot: Int? = if (fainted) null else 0,
        ) = BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(),
            side = side,
            activeSlot = activeSlot,
            speciesId = "showdown:test",
            formId = null,
            level = 50,
            hpFraction = hp,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = emptySet(),
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = fainted,
        )
        val state = BattleStateView(
            battleId = battleId,
            format = BattleFormat.SINGLE,
            turn = 3,
            pokemon = listOf(
                pokemon(BattleSide.ALLY, 1.0, false),
                pokemon(BattleSide.ALLY, 1.0, false, activeSlot = null),
                pokemon(BattleSide.ALLY, 0.0, true),
                pokemon(BattleSide.OPPONENT, 1.0, false),
                pokemon(BattleSide.OPPONENT, 0.0, true),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val mind = LocalBattleMind.assess(null, battleId, context, jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile.balanced())

        assertEquals(0.0, mind.positionAdvantage, 0.000_001)
    }
}
