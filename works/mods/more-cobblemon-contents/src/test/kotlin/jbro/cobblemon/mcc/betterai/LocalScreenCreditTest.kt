package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalScreenCredit
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleMoveRequirementKind
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectCoverage
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectsView
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Screens priced by the hits they halve, on a recorded Boss position: Milotic in front of Ferrothorn. */
class LocalScreenCreditTest {
    @Test
    fun `a screen earns by the hits of its category and Aurora Veil only in snow`() {
        val context = prepared("milotic-ferrothorn-switch-in")
        fun credit(ctx: BattleDecisionContext, moveId: String): Double {
            val candidate = screen(moveId)
            val scores = LocalMatchupScoreCalculator.calculate(ctx)
            return LocalScreenCredit.adjustments(listOf(candidate), ctx, scores)[candidate.actionId] ?: 0.0
        }
        val reflect = credit(context, "reflect")
        val lightScreen = credit(context, "lightscreen")
        val veilDry = credit(context, "auroraveil")
        val snowy = context.copy(state = context.state.derive(field = BattleFieldStateView(
            BattleTimedEffectView("snow", 5), context.state.field.terrain, context.state.field.roomEffects,
            context.state.field.globalEffects, context.state.field.sideConditions)))
        val veilSnow = credit(snowy, "auroraveil")
        println("SCREENS reflect=$reflect lightscreen=$lightScreen auroraveil(dry)=$veilDry auroraveil(snow)=$veilSnow")
        // Ferrothorn hits with Power Whip, physical.
        assertTrue(reflect > lightScreen, "reflect $reflect light screen $lightScreen")
        assertTrue(veilDry < 0.0, "Aurora Veil without snow fails: $veilDry")
        assertTrue(veilSnow >= reflect, "Aurora Veil in snow covers both: $veilSnow vs $reflect")
    }

    @Test
    fun `a singles screen is not read as a doubles-only move`() {
        // The screens' damage modifier reads activePerHalf; only Follow Me's kind of check means the move fails alone.
        for (id in listOf("reflect", "lightscreen", "auroraveil"))
            assertTrue(MOVES.getValue(id).requirements.none { it.kind == BattleMoveRequirementKind.MULTIPLE_ACTIVE_POKEMON }, id)
        for (id in listOf("followme", "ragepowder", "afteryou", "quash", "spotlight"))
            assertTrue(MOVES.getValue(id).requirements.any { it.kind == BattleMoveRequirementKind.MULTIPLE_ACTIVE_POKEMON }, id)
        val context = prepared("milotic-ferrothorn-switch-in")
        val template = screen("reflect")
        val reflect = BattleActionCandidate(template.actionId, template.kind, template.actorSlot, template.moveSlot, "reflect",
            moveDetails = requireNotNull(template.moveDetails).copy(effects = MOVES.getValue("reflect")))
        val profile = BattleTrainerProfile(skillLevel = 5, personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS)
        val ranked = LocalBattleActionPolicy.rank(context.copy(candidates = context.candidates + reflect), null, profile)
        val rank = ranked.first { it.outcome.candidate.actionId == reflect.actionId }
        assertTrue(!rank.outcome.publiclyInert && rank.comparisonValue > 0.0, "reflect ${rank.comparisonValue}")
    }

    private fun screen(moveId: String) = BattleActionCandidate(
        actionId = "move:0:9:screen:$moveId",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 3,
        moveId = moveId,
        moveDetails = BattleMoveCandidateView("Psychic", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 20,
            BattleMoveTargetPattern.SIDE, BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(BattleMoveEffectView(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.USER_SIDE, valueId = moveId)),
                false)),
    )

    private companion object {
        val MOVES: Map<String, BattleMoveEffectsView> by lazy {
            java.util.zip.ZipInputStream(requireNotNull(LocalScreenCreditTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun prepared(name: String): BattleDecisionContext {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/$name.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
        val tier = BattleTrainerTier.BOSS
        val bound = raw.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(raw.state, raw.publicActionCatalog, tier,
            LocalOpponentMoveUsage.forFormat(raw.state.format)))
        return PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
    }
}
