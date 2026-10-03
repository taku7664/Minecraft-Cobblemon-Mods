package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.InBattleGimmickMove
import com.cobblemon.mod.common.battles.InBattleMove
import com.cobblemon.mod.common.battles.MoveTarget
import com.cobblemon.mod.common.battles.ShowdownMoveset
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173RequestSlotRulesTest {
    @Test
    fun `only the forced slot responds during a switch request`() {
        assertTrue(Cobblemon173RequestSlotRules.mustPass(true, false, true, false))
        assertFalse(Cobblemon173RequestSlotRules.mustPass(true, true, false, false))
        assertTrue(Cobblemon173RequestSlotRules.mustPass(false, false, false, false))
        assertTrue(Cobblemon173RequestSlotRules.mustPass(false, false, true, true))
        assertFalse(Cobblemon173RequestSlotRules.mustPass(false, false, true, false))
    }

    @Test
    fun `revival accepts fainted active and bench targets but no living targets`() {
        assertTrue(Cobblemon173RequestSlotRules.eligibleReplacement(true, false, true))
        assertTrue(Cobblemon173RequestSlotRules.eligibleReplacement(true, false, false))
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(true, true, false))
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(false, false, false))
        assertFalse(Cobblemon173RequestSlotRules.eligibleReplacement(false, true, true))
        assertTrue(Cobblemon173RequestSlotRules.eligibleReplacement(false, true, false))
        assertEquals(1, Cobblemon173RequestSlotRules.replacementCount(2, 1))
        assertEquals(2, Cobblemon173RequestSlotRules.replacementCount(2, 3))
    }

    @Test
    fun `continued dynamax reads current max move availability independently of its base move`() {
        val base = InBattleMove().also { it.id = "thunderwave"; it.disabled = true; it.pp = 10 }
        val max = InBattleGimmickMove().also { it.move = "maxguard"; it.target = MoveTarget.self; it.disabled = false }
        val moveset = ShowdownMoveset().also { it.canDynamax = false; it.maxMoves = listOf(max) }
        assertSame(max, Cobblemon173ActionCandidateAdapter.currentMaxMove(moveset, 0))
        assertTrue(Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(base, null, max))
        max.disabled = true
        assertFalse(Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(base, null, max))
        moveset.canDynamax = true
        assertNull(Cobblemon173ActionCandidateAdapter.currentMaxMove(moveset, 0))
    }

    @Test
    fun `shared target policy preserves existing friendly status and pollen puff exceptions`() {
        assertFalse(Cobblemon173ActionCandidateAdapter.isMoveTargetAllowed("closecombat", BattleSide.ALLY,
            MoveTarget.normal, BattleMoveDamageCategory.PHYSICAL))
        assertFalse(Cobblemon173ActionCandidateAdapter.isMoveTargetAllowed("beatup", BattleSide.ALLY,
            MoveTarget.normal, BattleMoveDamageCategory.PHYSICAL))
        assertTrue(Cobblemon173ActionCandidateAdapter.isMoveTargetAllowed("pollenpuff", BattleSide.ALLY,
            MoveTarget.normal, BattleMoveDamageCategory.SPECIAL))
        assertTrue(Cobblemon173ActionCandidateAdapter.isMoveTargetAllowed("skillswap", BattleSide.ALLY,
            MoveTarget.normal, BattleMoveDamageCategory.STATUS))
        assertTrue(Cobblemon173ActionCandidateAdapter.isMoveTargetAllowed("closecombat", BattleSide.OPPONENT,
            MoveTarget.normal, BattleMoveDamageCategory.PHYSICAL))
    }
}
