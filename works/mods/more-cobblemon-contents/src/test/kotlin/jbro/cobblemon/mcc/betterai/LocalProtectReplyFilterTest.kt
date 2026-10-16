package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.search.LocalEngineTurnReadout
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalProtectReplyFilterTest {
    @Test
    fun `unrevealed status replies are excluded but attacks and switches remain`() {
        val replies = listOf(
            move("revealed-status", "cobblemon:Thunder Wave", BattleMoveDamageCategory.STATUS),
            move("unrevealed-status", "cobblemon:Swords Dance", BattleMoveDamageCategory.STATUS),
            move("unrevealed-attack", "cobblemon:Thunderbolt", BattleMoveDamageCategory.SPECIAL),
            switch("switch"),
        )

        assertEquals(
            listOf("revealed-status", "unrevealed-attack", "switch"),
            LocalEngineTurnReadout.publicReplies(replies, setOf("showdown:thunderwave")).map { it.actionId },
        )
    }

    @Test
    fun `engine dex classifies replies without move details and unknown classifications remain`() {
        val replies = listOf(
            move("dex-status", "cobblemon:thunderwave", category = null),
            move("unknown", "fixture:not-in-dex", category = null),
        )

        assertEquals(
            listOf("unknown"),
            LocalEngineTurnReadout.publicReplies(replies, emptySet()).map { it.actionId },
        )
    }

    @Test
    fun `replies remain when the active opponent cannot be identified`() {
        val replies = listOf(move("status", "cobblemon:swordsdance", BattleMoveDamageCategory.STATUS))

        assertEquals(replies, LocalEngineTurnReadout.publicReplies(replies, knownMoveIds = null))
    }

    @Test
    fun `double replies filter every status component by its own actor slot`() {
        val allowed = composite(
            "allowed",
            move("slot-0-known", "cobblemon:thunderwave", BattleMoveDamageCategory.STATUS, actorSlot = 0),
            move("slot-1-known", "cobblemon:willowisp", BattleMoveDamageCategory.STATUS, actorSlot = 1),
        )
        val blocked = composite(
            "blocked",
            move("slot-0-known-2", "cobblemon:thunderwave", BattleMoveDamageCategory.STATUS, actorSlot = 0),
            move("slot-1-hidden", "cobblemon:swordsdance", BattleMoveDamageCategory.STATUS, actorSlot = 1),
        )
        val attack = composite(
            "attack",
            move("slot-0-attack", "cobblemon:thunderbolt", BattleMoveDamageCategory.SPECIAL, actorSlot = 0),
            switch("slot-1-switch", actorSlot = 1),
        )

        assertEquals(
            listOf("allowed", "attack"),
            LocalEngineTurnReadout.publicReplies(
                listOf(allowed, blocked, attack),
                mapOf(0 to setOf("showdown:thunderwave"), 1 to setOf("showdown:willowisp")),
            ).map { it.actionId },
        )
    }

    @Test
    fun `second turn uses the identified opponent public moves and treats an unseen opponent as empty`() {
        val previous = pokemon(UUID(0, 1), setOf("cobblemon:thunderwave"))
        val switched = pokemon(UUID(0, 2), setOf("cobblemon:willowisp"))
        val publicPokemon = listOf(previous, switched)

        assertEquals(
            setOf("cobblemon:willowisp"),
            LocalEngineTurnReadout.knownOpponentMoveIds(switched.battlePokemonId, publicPokemon),
        )
        assertEquals(emptySet<String>(), LocalEngineTurnReadout.knownOpponentMoveIds(UUID(0, 3), publicPokemon))
        assertEquals(null, LocalEngineTurnReadout.knownOpponentMoveIds(null, publicPokemon))
        assertEquals(
            mapOf(0 to setOf("cobblemon:willowisp"), 1 to emptySet()),
            LocalEngineTurnReadout.knownOpponentMoveIds(mapOf(0 to switched.battlePokemonId, 1 to UUID(0, 3)), publicPokemon),
        )
    }

    private fun move(id: String, moveId: String, category: BattleMoveDamageCategory?, actorSlot: Int = 0) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = moveId,
        moveDetails = category?.let {
            BattleMoveCandidateView("normal", it, if (it == BattleMoveDamageCategory.STATUS) 0.0 else 80.0, 100.0, 0, 10)
        },
    )

    private fun switch(id: String, actorSlot: Int = 0) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.SWITCH,
        actorSlot = actorSlot,
        switchPokemonId = UUID(0, 1),
    )

    private fun composite(id: String, vararg parts: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = parts.map { it.actionId },
        componentActions = parts.toList(),
    )

    private fun pokemon(id: UUID, knownMoves: Set<String>) = BattlePokemonStateView(
        battlePokemonId = id,
        side = BattleSide.OPPONENT,
        activeSlot = null,
        speciesId = "cobblemon:ditto",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = knownMoves,
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
    )
}
