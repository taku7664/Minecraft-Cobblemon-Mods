package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalMatchupSurvivalTest {
    @Test fun `landed use profiles retain miss probabilities and snapshot mutable damage sequences`() {
        val first = mutableListOf(0.75, 0.75)
        val rolls = mutableListOf<List<Double>>(first, listOf(0.25, 0.25))
        val original = LocalKnockoutProfile.byHits(rolls, 0.5, 1.0, 6)
        assertEquals(listOf(1.0, 1.0, 0.75, 0.5), original.survival.take(4))
        assertEquals(listOf(0.0, 0.375, 0.5, 0.5625), original.damageWhileStanding.take(4))
        first[0] = 0.5
        first[1] = 0.5
        val weaker = LocalKnockoutProfile.byHits(rolls, 0.5, 1.0, 6)
        assertEquals(1.0, weaker.survival[2])
        assertEquals(0.875, weaker.survival[3])
        val replayed = LocalKnockoutProfile.byHits(listOf(listOf(0.75, 0.75), listOf(0.25, 0.25)), 0.5, 1.0, 6)
        assertSame(original, replayed)
        assertNotSame(original, LocalKnockoutProfile.byHits(listOf(listOf(0.75), listOf(0.25)), 1.0, 1.0, 6))
        assertNotSame(original, LocalKnockoutProfile.byHits(listOf(listOf(0.75, 0.75), listOf(0.25, 0.25)), 0.5, 1.0, 6, 0.0))
    }

    @Test fun `known Sturdy and Sash stop the first knockout and preserve the next landed knockout`() {
        for ((ability,item) in listOf("sturdy" to "", "" to "focussash")) {
            val c = context(ability,item)
            val hit = incoming(c)
            assertEquals(0.0,hit.knockoutChanceWithin(1),1e-9)
            assertEquals(1.0,hit.knockoutChanceWithin(2),1e-9)
            assertEquals(0.99,hit.maximumDamageFraction,1e-9)
            assertEquals(1.0,LocalActingChance.of(c.candidates.single(),c,MatchupScores(emptyMap(),emptyMap(),true)),1e-9)
        }
    }

    @Test fun `damage already taken removes the full health protection`() {
        assertEquals(1.0,incoming(context("sturdy","",hp=0.99)).knockoutChanceWithin(1),1e-9)
        assertEquals(1.0,incoming(context("","focussash",hp=0.99)).knockoutChanceWithin(1),1e-9)
    }

    @Test fun `Mold Breaker bypasses Sturdy but not the Sash`() {
        assertEquals(1.0,incoming(context("sturdy","",foeAbility="moldbreaker")).knockoutChanceWithin(1),1e-9)
        assertEquals(0.0,incoming(context("sturdy","focussash",foeAbility="moldbreaker")).knockoutChanceWithin(1),1e-9)
    }

    @Test fun `a declared multi hit attack still knocks out through full health survival`() {
        assertEquals(1.0,incoming(context("sturdy","",multi=true)).knockoutChanceWithin(1),1e-9)
    }

    @Test fun `a miss does not consume survival and two landed hits are needed`() {
        val p=LocalKnockoutProfile.of(listOf(1.0),0.5,1.0,12,0.99)
        assertEquals(1.0,p.survival[1],1e-9)
        assertEquals(0.75,p.survival[2],1e-9)
        assertEquals(0.5,p.survival[3],1e-9)
        assertEquals(4.0,p.expectedUses,1e-9)
    }

    @Test fun `one HP targets consume the Sash even though their HP fraction remains full`() {
        val p=LocalKnockoutProfile.of(listOf(1.0),1.0,1.0,4,0.0)
        assertEquals(listOf(1.0,1.0,0.0),p.survival.take(3))
        assertEquals(2.0,p.expectedUses,1e-9)
    }

    @Test fun `a nonlethal first hit gets no extra free turn and a one use attacker cannot return`() {
        val p=LocalKnockoutProfile.of(listOf(0.4),1.0,1.0,6,0.99)
        assertEquals(listOf(1.0,1.0,1.0,0.0),p.survival.take(4))
        val once=LocalKnockoutProfile.once(listOf(1.0),1.0,1.0,6,0.99)
        assertTrue(once.survival.all {it==1.0})
        assertTrue(once.expectedUses.isInfinite())
    }

    private fun incoming(c:BattleDecisionContext):MoveMatchupScore {
        val user=c.state.pokemon.first {it.side==BattleSide.ALLY}
        val foe=c.state.pokemon.first {it.side==BattleSide.OPPONENT}
        return LocalMatchupScoreCalculator.moveMatchups(c,foe.battlePokemonId,user.battlePokemonId,LocalProjectedActionCalculationCache()).single().score
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
