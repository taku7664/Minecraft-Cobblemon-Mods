package jbro.cobblemon.morebattlecontent.internal.factory

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FactoryRentalRulesTest {
    @Test
    fun `single selects three and double selects four from an immutable six-set draft`() {
        val raw = (1..6).map(::rental).toMutableList()
        val draft = FactoryRentalDraft(raw)
        raw.clear()

        assertEquals(6, draft.sets.size)
        assertEquals(3, draft.select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE).sets.size)
        assertEquals(4, draft.select(listOf("set1", "set2", "set3", "set4"), FactoryBattleFormat.DOUBLE).sets.size)
        assertThrows<IllegalArgumentException> { draft.select(listOf("set1", "set2"), FactoryBattleFormat.SINGLE) }
        assertThrows<IllegalArgumentException> { draft.select(listOf("set1", "set1", "set2"), FactoryBattleFormat.SINGLE) }
    }

    @Test
    fun `drafts reject duplicate species but allow duplicate held items`() {
        assertThrows<IllegalArgumentException> {
            FactoryRentalDraft(
                (1..6).map(::rental).toMutableList().also { it[5] = rental(6, species = "cobblemon:species1") },
            )
        }
        val draft = FactoryRentalDraft((1..6).map { rental(it, item = "cobblemon:leftovers") })
        val team = draft.select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)

        assertEquals(1, draft.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
        assertEquals(1, team.sets.mapNotNull(FactoryRentalSet::heldItemId).distinct().size)
    }

    @Test
    fun `rental sets require battle-defining data and legal stat ranges`() {
        assertThrows<IllegalArgumentException> { rental(1, moves = emptyList()) }
        assertThrows<IllegalArgumentException> { rental(1, moves = listOf("a", "b", "c", "d", "e")) }
        assertThrows<IllegalArgumentException> { rental(1, ivs = FactoryStatSpread(32, 0, 0, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { rental(1, evs = FactoryStatSpread(252, 252, 252, 0, 0, 0)) }
    }

    @Test
    fun `hgss progression uses seven-battle rounds iv steps trade elevations and single bosses`() {
        assertEquals(listOf(0, 4, 8, 12, 16, 20, 24, 31, 31), (1..9).map(FactoryProgression::uniformIvForRound))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 5), listOf(1, 7, 14, 21, 28, 35, 70).map(FactoryProgression::strongerOfferCount))
        assertEquals(1, FactoryProgression.roundForBattle(7))
        assertEquals(2, FactoryProgression.roundForBattle(8))
        assertTrue(FactoryProgression.isFactoryHeadBattle(21, FactoryBattleFormat.SINGLE))
        assertTrue(FactoryProgression.isFactoryHeadBattle(49, FactoryBattleFormat.SINGLE))
        assertFalse(FactoryProgression.isFactoryHeadBattle(21, FactoryBattleFormat.DOUBLE))
    }

    @Test
    fun `level 50 and open level use distinct original-style pool progressions`() {
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.STARTER, setOf(1)),
            FactoryProgression.poolWindow(FactoryLevelMode.LEVEL_50, 1),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(1)),
            FactoryProgression.poolWindow(FactoryLevelMode.LEVEL_50, 2),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.INTERMEDIATE, setOf(2)),
            FactoryProgression.poolWindow(FactoryLevelMode.LEVEL_50, 3),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)),
            FactoryProgression.poolWindow(FactoryLevelMode.LEVEL_50, 4),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1, 2, 3, 4)),
            FactoryProgression.poolWindow(FactoryLevelMode.LEVEL_50, 8),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1)),
            FactoryProgression.poolWindow(FactoryLevelMode.OPEN_LEVEL, 1),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(4)),
            FactoryProgression.poolWindow(FactoryLevelMode.OPEN_LEVEL, 4),
        )
        assertEquals(
            FactoryPoolWindow(FactoryPoolGroup.ADVANCED, setOf(1, 2, 3, 4)),
            FactoryProgression.poolWindow(FactoryLevelMode.OPEN_LEVEL, 5),
        )
    }

    @Test
    fun `victory heals then exposes only observed swap information and applies at most one legal trade`() {
        val team = FactoryRentalDraft((1..6).map(::rental))
            .select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)
        var healed = 0
        val session = FactoryRunSession(
            UUID.randomUUID(),
            team,
            FactoryLevelMode.LEVEL_50,
            healRentals = { healed++ },
        )
        val opponent = mapOf(
            UUID.fromString("11111111-1111-1111-1111-111111111111") to rental(7),
            UUID.fromString("22222222-2222-2222-2222-222222222222") to rental(8),
            UUID.fromString("33333333-3333-3333-3333-333333333333") to rental(9),
        )

        val battleId = UUID.randomUUID()
        session.beginBattle(battleId)
        val offers = session.recordVictory(
            battleId,
            opponent,
            mapOf(
                "set7" to FactoryOpponentObservation(
                    "cobblemon:species7",
                    setOf("cobblemon:move7a"),
                    revealedAbilityId = null,
                    revealedHeldItemId = null,
                ),
            ),
        )

        assertEquals(1, healed)
        assertEquals(1, session.wins)
        assertEquals("cobblemon:species7", offers.first().speciesId)
        assertEquals(setOf("cobblemon:move7a"), offers.first().revealedMoveIds)
        assertEquals(null, offers.first().revealedAbilityId)
        assertEquals(null, offers.first().revealedHeldItemId)
        session.swap("set1", offers.first().token)
        assertEquals(listOf("set2", "set3", "set7"), session.team.sets.map { it.setId })
        assertEquals(2, session.rentAndTradeCount)
        assertEquals(FactoryRunPhase.READY, session.phase)
        assertThrows<IllegalStateException> { session.swap("set2", offers[1].token) }
    }

    @Test
    fun `swap allows an incoming rental to repeat a remaining held item`() {
        val draft = FactoryRentalDraft((1..6).map(::rental))
        val team = draft.select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)
        val session = FactoryRunSession(UUID.randomUUID(), team, FactoryLevelMode.LEVEL_50, healRentals = {})
        val battleId = UUID.randomUUID()
        val incomingToken = UUID(1, 7)
        val opponent = mapOf(
            incomingToken to rental(7, item = "cobblemon:item2"),
            UUID(1, 8) to rental(8),
            UUID(1, 9) to rental(9),
        )
        session.beginBattle(battleId)
        session.recordVictory(battleId, opponent, emptyMap())

        session.swap("set3", incomingToken)

        assertEquals(listOf("cobblemon:item1", "cobblemon:item2", "cobblemon:item2"), session.team.sets.map(FactoryRentalSet::heldItemId))
        assertEquals(FactoryRunPhase.READY, session.phase)
    }

    @Test
    fun `invalid opponent observations fail before healing or mutating the run`() {
        val team = FactoryRentalDraft((1..6).map(::rental))
            .select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)
        var healed = 0
        val session = FactoryRunSession(
            UUID.randomUUID(),
            team,
            FactoryLevelMode.LEVEL_50,
            healRentals = { healed++ },
        )
        val opponent = mapOf(
            UUID.fromString("11111111-1111-1111-1111-111111111111") to rental(7),
            UUID.fromString("22222222-2222-2222-2222-222222222222") to rental(8),
            UUID.fromString("33333333-3333-3333-3333-333333333333") to rental(9),
        )
        val battleId = UUID.randomUUID()
        session.beginBattle(battleId)

        assertThrows<IllegalArgumentException> {
            session.recordVictory(
                battleId,
                opponent,
                mapOf("set7" to FactoryOpponentObservation("cobblemon:not_species7", emptySet(), null, null)),
            )
        }

        assertEquals(0, healed)
        assertEquals(0, session.wins)
        assertEquals(FactoryRunPhase.IN_BATTLE, session.phase)
    }

    @Test
    fun `swap offers expose exact public ability and item but reject values outside the rental set`() {
        val team = FactoryRentalDraft((1..6).map(::rental))
            .select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)
        val session = FactoryRunSession(UUID.randomUUID(), team, FactoryLevelMode.LEVEL_50, healRentals = {})
        val battleId = UUID.randomUUID()
        val opponent = mapOf(
            UUID(1, 7) to rental(7),
            UUID(1, 8) to rental(8),
            UUID(1, 9) to rental(9),
        )
        session.beginBattle(battleId)

        val offers = session.recordVictory(
            battleId,
            opponent,
            mapOf(
                "set7" to FactoryOpponentObservation(
                    "cobblemon:species7",
                    emptySet(),
                    "cobblemon:ability7",
                    "cobblemon:item7",
                ),
            ),
        )

        assertEquals("cobblemon:ability7", offers.first().revealedAbilityId)
        assertEquals("cobblemon:item7", offers.first().revealedHeldItemId)
    }

    @Test
    fun `seventh victory ends the round and requires a fresh six rental draft`() {
        val team = FactoryRentalDraft((1..6).map(::rental))
            .select(listOf("set1", "set2", "set3"), FactoryBattleFormat.SINGLE)
        val opponent = mapOf(
            UUID(1, 7) to rental(7),
            UUID(1, 8) to rental(8),
            UUID(1, 9) to rental(9),
        )
        val nextDraft = FactoryRentalDraft((11..16).map(::rental))
        var requestedRound = 0
        val session = FactoryRunSession(
            UUID.randomUUID(),
            team,
            FactoryLevelMode.LEVEL_50,
            {},
        ) { _, round, _ ->
            requestedRound = round
            nextDraft
        }
        repeat(7) { battleIndex ->
            val battleId = UUID.randomUUID()
            session.beginBattle(battleId)
            session.recordVictory(battleId, opponent, emptyMap())
            if (battleIndex < 6) session.keepTeam()
        }

        assertEquals(FactoryRunPhase.DRAFT_SELECTION, session.phase)
        assertEquals(2, requestedRound)
        assertEquals((11..16).map { "set$it" }, session.pendingDraft!!.sets.map(FactoryRentalSet::setId))
        assertEquals(emptyList<FactorySwapOffer>(), session.swapOffers)

        session.selectDraft(listOf("set11", "set12", "set13"))
        assertEquals(FactoryRunPhase.READY, session.phase)
        assertEquals(listOf("set11", "set12", "set13"), session.team.sets.map(FactoryRentalSet::setId))
    }

    private fun rental(
        index: Int,
        species: String = "cobblemon:species$index",
        item: String? = "cobblemon:item$index",
        moves: List<String> = listOf("cobblemon:move${index}a", "cobblemon:move${index}b"),
        ivs: FactoryStatSpread = FactoryStatSpread(0, 0, 0, 0, 0, 0),
        evs: FactoryStatSpread = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    ) = FactoryRentalSet(
        "set$index",
        species,
        moves,
        "cobblemon:ability$index",
        item,
        "cobblemon:nature$index",
        ivs,
        evs,
    )
}
