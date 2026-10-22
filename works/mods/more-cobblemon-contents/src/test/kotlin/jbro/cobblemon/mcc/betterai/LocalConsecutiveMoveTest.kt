package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalConsecutiveMoveTest {
    @Test fun `repeat restriction is read from callback shape rather than a move name`() {
        val parsed=BattleDeclarativeMoveEffects.parse("""const Moves = { sample: { accuracy: true, basePower: 0,
            category: "Status", target: "self", onPrepareHit(user) { return !user.removeVolatile("marker"); } } };""")
        assertTrue("repeat_fails_while_volatile:marker" in parsed.getValue("sample").mechanicFlags)
        assertTrue("destinybond" in LocalMoveCode.repeatFailureVolatiles)
    }

    @Test fun `Destiny Bond has no own value while the prior bond remains and is available after it is gone`() {
        val f=fixture("destinybond",volatiles=setOf("destinybond"))
        assertFalse(LocalConsecutiveMoveRules.canAttempt(f.action,f.context.state,f.actor))
        assertEquals(0.0,LocalNonDamagingMoveEvaluator.score(f.action,f.context,1.0).total,1e-9)
        assertEquals(0.0,LocalActingChance.of(f.action,f.context,MatchupScores(emptyMap(),emptyMap(),true)),1e-9)
        val next=fixture("destinybond",previous="destinybond")
        assertTrue(LocalConsecutiveMoveRules.canAttempt(next.action,next.context.state,next.actor))
    }

    @Test fun `all cantusetwice moves use the actor's last move and a different move resets them`() {
        for(id in listOf("bloodmoon","gigatonhammer")) {
            val repeated=fixture(id,previous=id)
            assertTrue(LocalConsecutiveMoveRules.needsGap(repeated.action,repeated.actor))
            assertFalse(LocalConsecutiveMoveRules.canAttempt(repeated.action,repeated.context.state,repeated.actor))
            val changed=fixture(id,previous="protect")
            assertTrue(LocalConsecutiveMoveRules.canAttempt(changed.action,changed.context.state,changed.actor))
        }
    }

    @Test fun `switching resets repeat history and another actor's move cannot block this one`() {
        val switched=fixture("bloodmoon",previous="bloodmoon",switched=true)
        assertTrue(LocalConsecutiveMoveRules.canAttempt(switched.action,switched.context.state,switched.actor))
        val f=fixture("bloodmoon")
        val other=f.context.state.observedEvents+BattleObservedEventView(4,2,BattleObservedEventKind.MOVE_USED,FOE,publicValueId="bloodmoon")
        val state=BattleStateView(f.context.state.battleId,BattleFormat.SINGLE,3,f.context.state.pokemon,f.context.state.field,
            f.context.state.remainingPokemonBySide,other,emptyList())
        assertTrue(LocalConsecutiveMoveRules.canAttempt(f.action,state,f.actor))
    }

    @Test fun `Max moves do not inherit a base move's repeat restriction`() {
        for(id in listOf("bloodmoon","destinybond")) {
            val f=fixture(id,previous=id,volatiles=setOf("dynamax","destinybond"))
            assertTrue(LocalConsecutiveMoveRules.canAttempt(f.action,f.context.state,f.actor))
            assertFalse(LocalConsecutiveMoveRules.needsGap(f.action,f.actor))
        }
    }

    @Test fun `Protect family keeps its shared chance and ramping attacks are not treated as forbidden repeats`() {
        for(id in listOf("protect","detect","kingsshield","endure")) {
            val f=fixture(id,previous=id)
            assertTrue(LocalConsecutiveMoveRules.canAttempt(f.action,f.context.state,f.actor))
            assertFalse(LocalConsecutiveMoveRules.needsGap(f.action,f.actor))
            assertTrue(LocalStallingProtectionRules.isStallingProtection(f.action),id)
        }
        assertEquals(1.0/3,LocalStallingProtectionRules.nextSuccessProbability(1),1e-9)
        for(id in listOf("furycutter","echoedvoice","rollout")) {
            val f=fixture(id,previous=id)
            assertTrue(LocalConsecutiveMoveRules.canAttempt(f.action,f.context.state,f.actor))
        }
    }

    @Test fun `a cooldown prices real turn spacing rather than consecutive attack uses`() {
        val plain=LocalKnockoutProfile.of(listOf(0.6),1.0,1.0,6)
        val gap=plain.withGapBetweenUses()
        assertEquals(listOf(1.0,1.0,1.0,0.0),gap.survival.take(4))
        assertEquals(3.0,gap.expectedUses,1e-9)
        val misses=LocalKnockoutProfile.of(listOf(0.6),0.5,1.0,20).withGapBetweenUses()
        assertEquals(7.0,misses.expectedUses,1e-4)
    }

    @Test fun `an unavailable attack earns no KO material or prevented-hit denial`() {
        val f=fixture("bloodmoon",previous="bloodmoon")
        try {
            LocalPokemonWorth.set(mapOf(FOE to 2.0))
            assertEquals(0.0,LocalPokemonWorth.knockoutScore(FOE,LocalDecisionTuning.CURRENT,f.action,f.context.state,1.0),1e-9)
            LocalPokemonWorth.set(mapOf(FOE to 2.0),mapOf(FOE to LocalKnockoutWorth.Denial(1.0,0,threat=0.0)))
            assertEquals(0.0,LocalPokemonWorth.knockoutScore(FOE,LocalDecisionTuning.CURRENT,f.action,f.context.state,1.0),1e-9)
        } finally {LocalPokemonWorth.clear()}
    }

    private data class Fixture(val actor:BattlePokemonStateView,val action:BattleActionCandidate,val context:BattleDecisionContext)
    private fun fixture(id:String,previous:String?=null,volatiles:Set<String> = emptySet(),switched:Boolean=false):Fixture {
        val actor=BattlePokemonStateView(OWN,BattleSide.ALLY,0,"fixture:repeat",null,50,1.0,null,emptyMap(),emptySet(),"","",false,
            knownTypeIds=setOf("normal"),knownVolatileEffectIds=volatiles)
        val foe=BattlePokemonStateView(FOE,BattleSide.OPPONENT,0,"fixture:repeat",null,50,1.0,null,emptyMap(),emptySet(),null,null,false)
        val category=if(id in listOf("bloodmoon","gigatonhammer","furycutter","echoedvoice","rollout"))BattleMoveDamageCategory.PHYSICAL else BattleMoveDamageCategory.STATUS
        val details=BattleMoveCandidateView("normal",category,if(category==BattleMoveDamageCategory.STATUS)0.0 else 140.0,100.0,0,8,
            effects=BattleShowdownMoveEffects.resolve(id))
        val action=BattleActionCandidate(id,BattleActionKind.USE_MOVE,0,0,id,moveDetails=details)
        val events=listOfNotNull(previous?.let {BattleObservedEventView(1,2,BattleObservedEventKind.MOVE_USED,OWN,publicValueId=it)},
            if(switched)BattleObservedEventView(2,2,BattleObservedEventKind.SWITCHED,OWN) else null)
        val state=BattleStateView(UUID.randomUUID(),BattleFormat.SINGLE,3,listOf(actor,foe),BattleFieldStateView.empty(),
            BattleSide.entries.associateWith {1},events,emptyList())
        return Fixture(actor,action,BattleDecisionContext(UUID.randomUUID(),state,listOf(action),Long.MAX_VALUE).withDynamaxInBattle(false))
    }
    private companion object {val OWN=UUID(0,1);val FOE=UUID(0,2)}
}
