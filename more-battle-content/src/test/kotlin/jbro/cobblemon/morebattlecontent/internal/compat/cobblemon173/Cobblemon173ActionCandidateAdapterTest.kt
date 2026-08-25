package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.PassActionResponse
import com.cobblemon.mod.common.battles.InBattleGimmickMove
import com.cobblemon.mod.common.battles.InBattleMove
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.battles.MoveTarget
import com.cobblemon.mod.common.api.moves.Moves
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173ActionCandidateAdapterTest {
    @Test
    fun `public move effects use embedded data but suppress a datapack override`() {
        val recover = requireNotNull(Cobblemon173ActionCandidateAdapter.publicMoveEffects("cobblemon:recover"))
        assertTrue(recover.effects.any { it.kind == BattleMoveEffectKind.HEAL_FRACTION })

        val movesClass = Moves::class.java
        val instance = movesClass.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        val scripts = movesClass.getMethod("getMoveScripts\$common").invoke(instance) as MutableMap<String, String>
        val previous = scripts.put("recover", "({ customOverride: true })")
        try {
            assertNull(Cobblemon173ActionCandidateAdapter.publicMoveEffects("cobblemon:recover"))
        } finally {
            if (previous == null) scripts.remove("recover") else scripts["recover"] = previous
        }
    }

    @Test
    fun `initial wait request submits zero responses like the Cobblemon AI actor`() {
        val request = ShowdownActionRequest().also {
            it.wait = true
            it.active = null
            it.forceSwitch = emptyList()
        }

        assertTrue(Cobblemon173ActionCandidateAdapter.passResponses(request, emptyList()).isEmpty())
    }

    @Test
    fun `selected major mechanic maps to the exact Cobblemon gimmick`() {
        val moveset = ShowdownMoveset().also {
            it.canMegaEvo = true
            it.canDynamax = true
            it.canTerastallize = "fire"
            it.canUltraBurst = true
            it.canZMove = emptyList()
        }

        assertEquals(
            ShowdownMoveset.Gimmick.MEGA_EVOLUTION,
            Cobblemon173ActionCandidateAdapter.allowedGimmick(
                moveset,
                Cobblemon173MechanicPolicy(MajorBattleMechanic.MEGA, consumed = false),
            ),
        )
        assertEquals(
            ShowdownMoveset.Gimmick.DYNAMAX,
            Cobblemon173ActionCandidateAdapter.allowedGimmick(
                moveset,
                Cobblemon173MechanicPolicy(MajorBattleMechanic.DYNAMAX, consumed = false),
            ),
        )
        assertEquals(
            ShowdownMoveset.Gimmick.TERASTALLIZATION,
            Cobblemon173ActionCandidateAdapter.allowedGimmick(
                moveset,
                Cobblemon173MechanicPolicy(MajorBattleMechanic.TERA, consumed = false),
            ),
        )
        assertNull(
            Cobblemon173ActionCandidateAdapter.allowedGimmick(
                moveset,
                Cobblemon173MechanicPolicy(MajorBattleMechanic.MEGA, consumed = true),
            ),
        )
    }

    @Test
    fun `double combinations reject duplicate switches and two mechanic uses`() {
        val sharedSwitch = UUID.randomUUID()
        val slotZero = listOf(
            switchChoice(0, sharedSwitch),
            moveChoice(0, mechanicId = "mega"),
            moveChoice(0),
        )
        val slotOne = listOf(
            switchChoice(1, sharedSwitch),
            moveChoice(1, mechanicId = "mega"),
            moveChoice(1),
        )

        val combined = Cobblemon173ActionCandidateAdapter.combine(listOf(slotZero, slotOne))

        assertTrue(combined.none { choice ->
            choice.candidate.componentActionIds == listOf(
                "switch:0:$sharedSwitch",
                "switch:1:$sharedSwitch",
            )
        })
        assertTrue(combined.none { choice ->
            choice.componentCandidates.count { it.mechanic != null } > 1
        })
        assertTrue(combined.all { it.responses.size == 2 })
        assertTrue(combined.all { it.candidate.componentActions == it.componentCandidates })
        assertEquals(combined.size, combined.map { it.candidate.actionId }.distinct().size)
    }

    @Test
    fun `preparation returns copied original responses only for known action ids`() {
        val choice = moveChoice(0)
        val preparation = Cobblemon173ActionPreparation.ready(listOf(choice))

        assertEquals(listOf(PassActionResponse), preparation.responsesFor(choice.candidate.actionId))
        assertNull(preparation.responsesFor("invented"))
    }

    @Test
    fun `Cobblemon always hit accuracy is normalized for the public AI contract`() {
        assertEquals(100.0, Cobblemon173ActionCandidateAdapter.publicAccuracy(-1.0))
        assertEquals(85.0, Cobblemon173ActionCandidateAdapter.publicAccuracy(85.0))
    }

    @Test
    fun `Cobblemon spread targets map to provider neutral public patterns`() {
        assertEquals(
            BattleMoveTargetPattern.ALL_ADJACENT,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.allAdjacent),
        )
        assertEquals(
            BattleMoveTargetPattern.ALL_OPPONENTS,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.allAdjacentFoes),
        )
        assertEquals(
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.normal),
        )
        assertEquals(
            BattleMoveTargetPattern.SELECTED_ALLY,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.adjacentAlly),
        )
        assertEquals(
            BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.adjacentAllyOrSelf),
        )
        assertEquals(
            BattleMoveTargetPattern.RANDOM_OPPONENT,
            Cobblemon173ActionCandidateAdapter.publicTargetPattern(MoveTarget.randomNormal),
        )
    }

    @Test
    fun `disabled base move is excluded unless the selected dynamax move is independently available`() {
        val move = InBattleMove().also {
            it.id = "move 1"
            it.move = "extremespeed"
            it.pp = 5
            it.maxpp = 5
            it.target = MoveTarget.normal
            it.disabled = true
        }
        val maxMove = InBattleGimmickMove().also {
            it.move = "maxstrike"
            it.target = MoveTarget.normal
            it.disabled = false
        }

        assertFalse(Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(move, null, null))
        assertFalse(
            Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(
                move,
                ShowdownMoveset.Gimmick.MEGA_EVOLUTION,
                null,
            ),
        )
        assertFalse(
            Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(
                move,
                ShowdownMoveset.Gimmick.TERASTALLIZATION,
                null,
            ),
        )
        assertTrue(
            Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(
                move,
                ShowdownMoveset.Gimmick.DYNAMAX,
                maxMove,
            ),
        )

        maxMove.disabled = true
        assertFalse(
            Cobblemon173ActionCandidateAdapter.isMoveChoiceAvailable(
                move,
                ShowdownMoveset.Gimmick.DYNAMAX,
                maxMove,
            ),
        )
    }

    private fun switchChoice(slot: Int, target: UUID) = Cobblemon173ActionChoice(
        candidate = BattleActionCandidate(
            actionId = "switch:$slot:$target",
            kind = BattleActionKind.SWITCH,
            actorSlot = slot,
            switchPokemonId = target,
        ),
        responses = listOf(PassActionResponse),
    )

    private fun moveChoice(slot: Int, mechanicId: String? = null) = Cobblemon173ActionChoice(
        candidate = BattleActionCandidate(
            actionId = "move:$slot:0:auto:${mechanicId ?: "base"}",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = slot,
            moveSlot = 0,
            moveId = "tackle",
            mechanic = mechanicId?.let { BattleMechanicCandidate(it, null, null) },
        ),
        responses = listOf(PassActionResponse),
    )
}
