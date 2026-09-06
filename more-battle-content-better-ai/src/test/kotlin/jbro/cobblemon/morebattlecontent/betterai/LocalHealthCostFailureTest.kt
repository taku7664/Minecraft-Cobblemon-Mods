package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalIdleUtilityMoveRules
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalHealthCostFailureTest {
    private fun context(hp: Int? = null, move: String = "bellydrum"): BattleDecisionContext {
        val input = javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        hp?.let { value ->
            val actor = input.getAsJsonObject("request").getAsJsonObject("side").getAsJsonArray("pokemon")
                .single { it.asJsonObject["active"].asBoolean }.asJsonObject
            actor.addProperty("condition", "$value/200")
        }
        if (move != "bellydrum") {
            input.getAsJsonArray("actions")[0].asJsonObject.addProperty("moveId", move)
            input.getAsJsonObject("moves").add(move, input.getAsJsonObject("moves")["bellydrum"].deepCopy())
        }
        return EmbeddedTeamInput.context(input, UUID.nameUUIDFromBytes("[60975,31127,3347,38767]".toByteArray()), 5, 0)
    }

    @Test
    fun `native low health failure is inert in policy not just zero status utility`() {
        val context = PublicBattleTacticalCalculator.calculate(context())
        val ranked = LocalBattleActionPolicy.rank(context, null, BattleTrainerProfile.balanced())
        assertEquals(context.candidates.size, ranked.size, "Failed moves stay in the legal candidate set")
        assertTrue(ranked.single { it.outcome.candidate.moveId == "bellydrum" }.outcome.publiclyInert)
        val brain = LocalTacticalBrain()
        val session = brain.openSession(BattleBrainOpenContext(context.state.battleId, BattleFormat.SINGLE))
        try {
            val choice = brain.decide(session, context).toCompletableFuture().get()
            assertNotEquals("move 1", choice.actionId)
        } finally { brain.closeSession(session, BattleBrainCloseResult(BattleBrainCloseOutcome.CANCELLED, 1)) }
    }

    @Test
    fun `unmodeled mechanic must not inherit the ordinary move health failure`() {
        val context = context()
        val candidate = BattleActionCandidate("mechanic-belly", BattleActionKind.USE_MOVE, actorSlot = 0,
            moveSlot = 0, moveId = "bellydrum", moveDetails = context.candidates.first().moveDetails,
            mechanic = BattleMechanicCandidate("zmove", null, 1))
        assertFalse(LocalIdleUtilityMoveRules.failsForInsufficientHp(candidate, context))
    }

    @Test
    fun `half health boosts fail through half hp but substitute retains quarter threshold`() {
        for (move in listOf("bellydrum", "filletaway")) {
            for (hp in listOf(30, 51, 99, 100)) {
                val context = context(hp, move)
                assertTrue(LocalIdleUtilityMoveRules.isIdle(context.candidates.first(), context), "$move at $hp/200")
            }
            val context = context(101, move)
            assertFalse(LocalIdleUtilityMoveRules.isIdle(context.candidates.first(), context))
        }
        for ((hp, expected) in listOf(50 to true, 51 to false, 100 to false)) {
            val context = context(hp, "substitute")
            assertEquals(expected, LocalIdleUtilityMoveRules.isIdle(context.candidates.first(), context))
        }
    }
}
