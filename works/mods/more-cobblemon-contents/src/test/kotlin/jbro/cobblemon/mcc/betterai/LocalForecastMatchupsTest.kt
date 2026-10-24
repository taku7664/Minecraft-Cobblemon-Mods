package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalForecastMatchupsTest {
    private val fast get()=LocalDecisionTuning.CURRENT.copy(simpleForecastMatchups=true)
    private fun changed(c:BattleDecisionContext,atk:Int=0,def:Int=0,hp:Double=1.0)=c.copy(state=c.state.copyState(pokemon=c.state.pokemon.map {
        if(it.side==BattleSide.ALLY)it.copyState(statStages=mapOf("attack" to atk),hpFraction=hp)
        else it.copyState(statStages=mapOf("defence" to def))
    }))
    @Test fun `seed prepares rolls without a full tactical matchup and direct pairs remain exact`() = LocalActiveTuning.with(fast) {
        val c=context("","",hp=1.0);val own=c.state.pokemon[0].battlePokemonId;val foe=c.state.pokemon[1].battlePokemonId
        LocalHpMatchupAudit.begin()
        try {
            LocalMatchupScoreCalculator.forecastMatchup(c,changed(c,atk=2),own,foe,MatchupSpeedField.CURRENT,LocalProjectedActionCalculationCache())
            val seed=LocalHpMatchupAudit.take()
            assertEquals(0,seed.tacticalCalls);assertEquals(0,seed.futureRollCalculations)
            assertTrue(seed.seedRollCalculations>0);assertEquals(seed.seedRollCalculations,seed.rollCalculations)
            LocalHpMatchupAudit.begin()
            LocalMatchupScoreCalculator.pairMatchup(c,own,foe,MatchupSpeedField.CURRENT,LocalProjectedActionCalculationCache())
            assertTrue(LocalHpMatchupAudit.take().tacticalCalls>0)
        } finally {LocalHpMatchupAudit.take()}
    }
    @Test fun `future rank and HP changes run no tactical or roll calculations after seeding`() = LocalActiveTuning.with(fast) {
        val c=context("","",hp=1.0);val cache=LocalProjectedActionCalculationCache()
        val own=c.state.pokemon.first {it.side==BattleSide.ALLY}.battlePokemonId
        val foe=c.state.pokemon.first {it.side==BattleSide.OPPONENT}.battlePokemonId
        LocalMatchupScoreCalculator.forecastMatchup(c,changed(c,atk=2),own,foe,MatchupSpeedField.CURRENT,cache)
        LocalHpMatchupAudit.begin()
        try {
            val result=LocalMatchupScoreCalculator.forecastMatchup(c,changed(c,atk=4,def=1,hp=.6),own,foe,MatchupSpeedField.CURRENT,cache)!!
            val count=LocalHpMatchupAudit.take()
            assertEquals(0,count.tacticalCalls);assertEquals(0,count.rollCalculations);assertEquals(0,count.seeds)
            assertEquals(1,count.futureCalls);assertEquals(1,count.simpleExchanges)
            assertTrue(result.score.isFinite());assertTrue(result.winProbability in 0.0..1.0)
        } finally {LocalHpMatchupAudit.take()}
    }
    @Test fun `future damage uses actual offensive and defensive multipliers`() {
        val c=context("","",hp=1.0);val own=c.state.pokemon.first {it.side==BattleSide.ALLY};val foe=c.state.pokemon.first {it.side==BattleSide.OPPONENT}
        val action=c.candidates.single()
        fun factor(board:BattleDecisionContext)=LocalForecastMatchups.damageFactor(action,board,own.battlePokemonId,foe.battlePokemonId)!!
        val base=factor(c)
        assertEquals(2.0,factor(changed(c,atk=2))/base,1e-12)
        assertEquals(.5,factor(changed(c,def=2))/base,1e-12)
        val unaware=c.copy(state=c.state.copyState(pokemon=c.state.pokemon.map {if(it.side==BattleSide.OPPONENT)it.copyState(knownAbilityId="unaware")else it}))
        assertEquals(factor(unaware),factor(changed(unaware,atk=2)),1e-12)
    }
    @Test fun `root action rank marginal is identical with forecast option on and off`() {
        val c=context("","",hp=1.0)
        val effect=BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,listOf(BattleMoveEffectView(
            BattleMoveEffectKind.STAT_STAGE,BattleMoveEffectTarget.USER,probability=1.0,statStages=mapOf("attack" to 2))),false)
        val action=BattleActionCandidate("setup",BattleActionKind.USE_MOVE,0,0,"swordsdance",moveDetails=BattleMoveCandidateView(
            "normal",BattleMoveDamageCategory.STATUS,0.0,100.0,0,8,BattleMoveTargetPattern.SELF,effects=effect))
        val before=LocalActiveTuning.with(LocalDecisionTuning.CURRENT) {LocalStatStageMarginalEvaluator.candidateScore(action,c,1.0)}
        val after=LocalActiveTuning.with(fast) {LocalStatStageMarginalEvaluator.candidateScore(action,c,1.0,tuning=fast)}
        assertNotNull(before);assertEquals(before,after)
    }
    @Test fun `small KO profile retains first KO miss and survival guards`() {
        val profile=LocalForecastMatchups.profile(listOf(.5,1.0),.8,1.0)
        assertEquals(.6,profile.survival[1],1e-12);assertEquals(1.875,profile.expectedUses,1e-12)
        val sash=LocalForecastMatchups.profile(listOf(1.0),.5,1.0,.99)
        assertEquals(1.0,sash.survival[1]);assertEquals(.75,sash.survival[2],1e-12);assertEquals(4.0,sash.expectedUses)
        assertTrue(LocalForecastMatchups.profile(listOf(0.0),1.0,1.0).expectedUses.isInfinite())
    }
    @Test fun `repeated self drops change the future required hits`() {
        assertEquals(2.0,LocalForecastMatchups.profile(listOf(.5),1.0,1.0).expectedUses)
        assertEquals(4.0,LocalForecastMatchups.profile(listOf(.5),1.0,1.0,means=listOf(.5,.25,.125)).expectedUses)
    }
    @Test fun `double spread seeds read each defenders own damage`() = LocalActiveTuning.with(fast) {
        val c=context("","");val own=c.state.pokemon[0];val foe=c.state.pokemon[1]
        val second=BattlePokemonStateView(UUID.randomUUID(),foe.side,1,foe.speciesId,null,50,1.0,null,
            emptyMap(),emptySet(),"","",false,knownTypeIds=foe.knownTypeIds,
            combatStats=foe.combatStats!!.copy(defence=BattleIntegerRange(240,240)))
        val details=BattleMoveCandidateView("normal",BattleMoveDamageCategory.PHYSICAL,10.0,100.0,0,8,BattleMoveTargetPattern.ALL_OPPONENTS)
        val board=c.copy(state=BattleStateView(c.state.battleId,BattleFormat.DOUBLE,c.state.turn,listOf(own,foe,second),
            c.state.field,BattleSide.entries.associateWith {if(it==BattleSide.ALLY)1 else 2},emptyList(),emptyList()),
            publicActionCatalog=BattlePublicActionCatalogView(listOf(BattlePokemonActionCatalogView(own.battlePokemonId,
                listOf(BattlePublicMoveOptionView("tackle",details,BattlePublicMoveKnowledge.EXACT_OWN)),true))))
        val cache=LocalProjectedActionCalculationCache()
        fun damage(target:UUID)=LocalForecastMatchups.forecastMoves(board,board,own.battlePokemonId,target,cache).single().score.maximumDamageFraction
        assertTrue(damage(foe.battlePokemonId)>damage(second.battlePokemonId)*2.5)
    }
    @Test fun `dynamax forecasts keep the base move key with max move mechanics`() = LocalActiveTuning.with(fast) {
        val c=context("","");val own=c.state.pokemon[0];val foe=c.state.pokemon[1]
        val board=c.copy(state=c.state.copyState(pokemon=c.state.pokemon.map {
            if(it.battlePokemonId==own.battlePokemonId)LocalDynamax.start(it,false)else it
        }))
        val move=LocalForecastMatchups.forecastMoves(board,board,own.battlePokemonId,foe.battlePokemonId,LocalProjectedActionCalculationCache()).single()
        assertEquals("tackle",move.score.moveId);assertEquals("maxstrike",move.action.moveId)
    }
    private fun context(ability:String,item:String,hp:Double=1.0,foeAbility:String="",multi:Boolean=false):BattleDecisionContext {
        fun mon(side:BattleSide,speed:Int,ab:String,it:String,h:Double)=BattlePokemonStateView(UUID.randomUUID(),side,0,
            "fixture:survival",null,50,h,null,emptyMap(),emptySet(),ab,it,false,knownTypeIds=setOf("normal"),
            combatStats=BattleCombatStatRangesView(BattleIntegerRange(100,100),BattleIntegerRange(300,300),BattleIntegerRange(60,60),
                BattleIntegerRange(100,100),BattleIntegerRange(60,60),BattleIntegerRange(speed,speed),
                if(side==BattleSide.ALLY)BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.LOCAL_OPPONENT_ESTIMATE))
        val user=mon(BattleSide.ALLY,100,ability,item,hp);val foe=mon(BattleSide.OPPONENT,400,foeAbility,"",1.0)
        val own=BattleMoveCandidateView("normal",BattleMoveDamageCategory.PHYSICAL,10.0,100.0,0,8,BattleMoveTargetPattern.SELECTED_OPPONENT)
        val action=BattleActionCandidate("wait-value",BattleActionKind.USE_MOVE,0,0,"tackle",moveDetails=own)
        val attack=BattleMoveCandidateView("normal",BattleMoveDamageCategory.PHYSICAL,10.0,100.0,0,8,
            effects=if(multi)BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,listOf(BattleMoveEffectView(
                BattleMoveEffectKind.MULTI_HIT,BattleMoveEffectTarget.SELECTED_TARGET,amountRange=BattleIntegerRange(2,2))),false) else null)
        val state=BattleStateView(UUID.randomUUID(),BattleFormat.SINGLE,3,listOf(user,foe),BattleFieldStateView.empty(),
            BattleSide.entries.associateWith {1},emptyList(),emptyList())
        return BattleDecisionContext(UUID.randomUUID(),state,listOf(action),Long.MAX_VALUE,publicActionCatalog=BattlePublicActionCatalogView(listOf(
            BattlePokemonActionCatalogView(user.battlePokemonId,listOf(BattlePublicMoveOptionView("tackle",own,BattlePublicMoveKnowledge.EXACT_OWN)),true),
            BattlePokemonActionCatalogView(foe.battlePokemonId,listOf(BattlePublicMoveOptionView("tackle",attack,BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),true))))
            .withDynamaxInBattle(false)
    }
}
