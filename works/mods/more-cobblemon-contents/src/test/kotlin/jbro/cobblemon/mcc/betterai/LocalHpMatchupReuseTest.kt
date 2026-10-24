package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalHpMatchupReuseTest {
    @Test fun `HP free keys erase only living HP without changing ordinary keys`() {
        val c=context("","",hp=.1);val healed=hp(c,.9)
        val fingerprints=LocalBattleStateFingerprint()
        val original=fingerprints.of(c.state)
        assertEquals(fingerprints.ofWithoutHp(c.state),fingerprints.ofWithoutHp(healed.state))
        assertNotEquals(original,fingerprints.of(healed.state))
        assertEquals(original,fingerprints.of(c.state))
        assertNotEquals(fingerprints.ofWithoutHp(c.state),fingerprints.ofWithoutHp(hp(c,0.0).state))
        assertEquals(fingerprints.ofWithoutHp(c.state),fingerprints.ofWithoutHp(c.state.copyState(pokemon=c.state.pokemon.reversed())))
    }
    private fun <T> enabled(block:()->T):T = LocalActiveTuning.with(LocalDecisionTuning.CURRENT.copy(simpleHpMatchups=true),block)
    private fun hp(c:BattleDecisionContext,value:Double)=c.copy(state=c.state.copyState(pokemon=c.state.pokemon.map {
        if(it.side==BattleSide.ALLY)it.copyState(hpFraction=value)else it
    }))

    @Test fun `HP changes skip tactical and raw damage calls and uncapped damage survives healing`() = enabled {
        val c=context("","",hp=.01);val healed=hp(c,.9);val cache=LocalProjectedActionCalculationCache()
        LocalHpMatchupAudit.begin()
        try {
            incoming(c,cache)
            val first=LocalHpMatchupAudit.take()
            LocalHpMatchupAudit.begin()
            val reused=incoming(healed,cache)
            val hit=LocalHpMatchupAudit.take()
            assertTrue(first.tacticalCalls>0)
            assertEquals(0,hit.tacticalCalls)
            assertEquals(0,hit.rollCalculations)
            assertEquals(1,hit.hpHits)
            val exact=LocalActiveTuning.with(LocalDecisionTuning.CURRENT) { incoming(healed) }
            assertEquals(exact,reused)
            assertTrue(reused.maximumDamageFraction>.01)
        } finally {LocalHpMatchupAudit.take()}
    }
    @Test fun `non HP changes require full calculation`() = enabled {
        val c=context("","",hp=.1);val cache=LocalProjectedActionCalculationCache();incoming(c,cache)
        val changed=hp(c,.8).let {it.copy(state=it.state.copyState(pokemon=it.state.pokemon.map {p->
            if(p.side==BattleSide.ALLY)p.copyState(statStages=mapOf("defence" to 1))else p
        }))}
        LocalHpMatchupAudit.begin()
        try {incoming(changed,cache);assertTrue(LocalHpMatchupAudit.take().tacticalCalls>0)}finally {LocalHpMatchupAudit.take()}
    }
    @Test fun `both actors HP may change when raw damage is HP independent`() = enabled {
        val c=context("","",hp=.1);val cache=LocalProjectedActionCalculationCache();incoming(c,cache)
        val changed=hp(c,.9).let {it.copy(state=it.state.copyState(pokemon=it.state.pokemon.map {p->
            if(p.side==BattleSide.OPPONENT)p.copyState(hpFraction=.7)else p
        }))}
        LocalHpMatchupAudit.begin()
        val reused=try {incoming(changed,cache)}finally {
            val count=LocalHpMatchupAudit.take();assertEquals(0,count.tacticalCalls);assertEquals(0,count.rollCalculations);assertEquals(1,count.hpHits)
        }
        assertEquals(LocalActiveTuning.with(LocalDecisionTuning.CURRENT) {incoming(changed)},reused)
    }
    @Test fun `HP sensitive attacker ability prevents reuse`() = enabled {
        val c=context("","",hp=.1,foeAbility="blaze");val cache=LocalProjectedActionCalculationCache();incoming(c,cache)
        LocalHpMatchupAudit.begin()
        try {
            incoming(hp(c,.9),cache)
            val count=LocalHpMatchupAudit.take();assertEquals(0,count.hpHits);assertTrue(count.tacticalCalls>0)
        } finally {LocalHpMatchupAudit.take()}
    }
    @Test fun `HP dependent survival mechanics stay on the full path`() = enabled {
        val c=context("sturdy","",hp=1.0);val cache=LocalProjectedActionCalculationCache()
        assertEquals(0.0,incoming(c,cache).knockoutChanceWithin(1),1e-9)
        LocalHpMatchupAudit.begin()
        try {
            assertEquals(1.0,incoming(hp(c,.8),cache).knockoutChanceWithin(1),1e-9)
            val count=LocalHpMatchupAudit.take();assertEquals(0,count.hpHits);assertTrue(count.tacticalCalls>0)
        } finally {LocalHpMatchupAudit.take()}
    }
    private fun incoming(c:BattleDecisionContext, cache:LocalProjectedActionCalculationCache=LocalProjectedActionCalculationCache()):MoveMatchupScore {
        val user=c.state.pokemon.first {it.side==BattleSide.ALLY}
        val foe=c.state.pokemon.first {it.side==BattleSide.OPPONENT}
        return LocalMatchupScoreCalculator.moveMatchups(c,foe.battlePokemonId,user.battlePokemonId,cache).single().score
    }
    private fun context(ability:String,item:String,hp:Double=1.0,foeAbility:String="",multi:Boolean=false):BattleDecisionContext {
        fun mon(side:BattleSide,speed:Int,ab:String,it:String,h:Double)=BattlePokemonStateView(UUID.randomUUID(),side,0,
            "fixture:survival",null,50,h,null,emptyMap(),emptySet(),ab,it,false,knownTypeIds=setOf("normal"),
            combatStats=BattleCombatStatRangesView(BattleIntegerRange(100,100),BattleIntegerRange(300,300),BattleIntegerRange(60,60),
                BattleIntegerRange(100,100),BattleIntegerRange(60,60),BattleIntegerRange(speed,speed),
                if(side==BattleSide.ALLY)BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.LOCAL_OPPONENT_ESTIMATE))
        val user=mon(BattleSide.ALLY,100,ability,item,hp);val foe=mon(BattleSide.OPPONENT,400,foeAbility,"",1.0)
        val own=BattleMoveCandidateView("normal",BattleMoveDamageCategory.STATUS,0.0,100.0,0,8,BattleMoveTargetPattern.SELF)
        val action=BattleActionCandidate("wait-value",BattleActionKind.USE_MOVE,0,0,"swordsdance",moveDetails=own)
        val attack=BattleMoveCandidateView("normal",BattleMoveDamageCategory.PHYSICAL,100.0,100.0,0,8,
            effects=if(multi)BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,listOf(BattleMoveEffectView(
                BattleMoveEffectKind.MULTI_HIT,BattleMoveEffectTarget.SELECTED_TARGET,amountRange=BattleIntegerRange(2,2))),false) else null)
        val state=BattleStateView(UUID.randomUUID(),BattleFormat.SINGLE,3,listOf(user,foe),BattleFieldStateView.empty(),
            BattleSide.entries.associateWith {1},emptyList(),emptyList())
        return BattleDecisionContext(UUID.randomUUID(),state,listOf(action),Long.MAX_VALUE,publicActionCatalog=BattlePublicActionCatalogView(listOf(
            BattlePokemonActionCatalogView(user.battlePokemonId,listOf(BattlePublicMoveOptionView("swordsdance",own,BattlePublicMoveKnowledge.EXACT_OWN)),true),
            BattlePokemonActionCatalogView(foe.battlePokemonId,listOf(BattlePublicMoveOptionView("tackle",attack,BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),true))))
            .withDynamaxInBattle(false)
    }
}
