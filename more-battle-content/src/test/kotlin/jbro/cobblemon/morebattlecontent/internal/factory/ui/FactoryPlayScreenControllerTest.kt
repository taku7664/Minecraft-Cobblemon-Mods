package jbro.cobblemon.morebattlecontent.internal.factory.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayError
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayPhase
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayView
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread
import jbro.cobblemon.morebattlecontent.internal.factory.FactorySwapOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryPlayScreenControllerTest {
    private val playerId = UUID.randomUUID()

    @Test
    fun `invalid selection feedback is trade specific only during the swap decision`() {
        assertEquals("invalid_swap", FactoryPlayError.INVALID_SELECTION.feedbackId(FactoryPlayPhase.SWAP_DECISION))
        assertEquals("invalid_selection", FactoryPlayError.INVALID_SELECTION.feedbackId(FactoryPlayPhase.INITIAL_DRAFT))
        assertEquals("wrong_phase", FactoryPlayError.WRONG_PHASE.feedbackId(FactoryPlayPhase.SWAP_DECISION))
    }

    @Test
    fun `rental cards toggle and submit exact visible pokemon without exposing set ids`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val state = view(FactoryPlayPhase.INITIAL_DRAFT, draft = (1..6).map(::rental))
        val controller = FactoryPlayScreenController(state, sent::add)

        assertTrue(controller.toggleRental("set1"))
        assertTrue(controller.toggleRental("set3"))
        assertTrue(controller.toggleRental("set6"))
        assertFalse(controller.toggleRental("missing"))
        assertTrue(controller.confirmSelection())

        val intent = sent.single() as FactoryPlayIntent.SelectRentals
        assertEquals(listOf("set1", "set3", "set6"), intent.setIds)
        assertTrue(controller.isPending)
    }

    @Test
    fun `start and swap actions use typed values selected by cards`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val available = FactoryPlayScreenController(view(FactoryPlayPhase.AVAILABLE), sent::add)
        available.chooseFormat(FactoryBattleFormat.DOUBLE)
        available.chooseLevelMode(FactoryLevelMode.OPEN_LEVEL)
        assertTrue(available.start())
        assertEquals(FactoryBattleFormat.DOUBLE, (sent.single() as FactoryPlayIntent.Start).format)
        assertEquals(FactoryLevelMode.OPEN_LEVEL, (sent.single() as FactoryPlayIntent.Start).levelMode)

        val incomingToken = UUID.randomUUID()
        val swapSent = ArrayList<FactoryPlayIntent>()
        val swap = FactoryPlayScreenController(
            view(
                FactoryPlayPhase.SWAP_DECISION,
                team = listOf(rental(1), rental(2), rental(3)),
                offers = listOf(FactorySwapOffer(incomingToken, "cobblemon:species4", emptySet(), null, null)),
            ),
            swapSent::add,
        )
        assertTrue(swap.chooseOutgoing("set2"))
        assertTrue(swap.chooseIncoming(incomingToken))
        assertTrue(swap.swap())
        assertEquals("set2", (swapSent.single() as FactoryPlayIntent.Swap).outgoingSetId)
        assertEquals(incomingToken, (swapSent.single() as FactoryPlayIntent.Swap).incomingToken)
    }

    @Test
    fun `ready team can request a server approved selection revision`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val controller = FactoryPlayScreenController(
            view(FactoryPlayPhase.READY, team = listOf(rental(1), rental(2), rental(3)), canRevise = true),
            sent::add,
        )

        assertTrue(controller.reviseSelection())
        assertTrue(sent.single() is FactoryPlayIntent.ReviseSelection)
    }

    @Test
    fun `ready team must choose every battle position before starting`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val controller = FactoryPlayScreenController(
            view(FactoryPlayPhase.READY, team = listOf(rental(1), rental(2), rental(3))),
            sent::add,
        )

        assertFalse(controller.beginBattle())
        assertFalse(controller.toggleBattleOrder("missing"))
        assertTrue(controller.toggleBattleOrder("set2"))
        assertTrue(controller.toggleBattleOrder("set1"))
        assertEquals(listOf("set2", "set1"), controller.battleOrderSetIds)
        assertFalse(controller.beginBattle())
        assertTrue(controller.toggleBattleOrder("set3"))
        assertTrue(controller.beginBattle())

        val intent = sent.single() as FactoryPlayIntent.BeginBattle
        assertEquals(listOf("set2", "set1", "set3"), intent.orderedSetIds)
    }

    @Test
    fun `clicking an ordered rental again removes it and compacts later positions`() {
        val controller = FactoryPlayScreenController(
            view(FactoryPlayPhase.READY, team = listOf(rental(1), rental(2), rental(3))),
            {},
        )

        controller.toggleBattleOrder("set2")
        controller.toggleBattleOrder("set1")
        controller.toggleBattleOrder("set3")
        assertTrue(controller.toggleBattleOrder("set1"))

        assertEquals(listOf("set2", "set3"), controller.battleOrderSetIds)
    }

    @Test
    fun `ready team without a retained draft cannot request a selection revision`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val controller = FactoryPlayScreenController(
            view(FactoryPlayPhase.READY, team = listOf(rental(1), rental(2), rental(3))),
            sent::add,
        )

        assertFalse(controller.reviseSelection())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `unconfirmed draft can be explicitly abandoned before requesting new rentals`() {
        val sent = ArrayList<FactoryPlayIntent>()
        val controller = FactoryPlayScreenController(
            view(FactoryPlayPhase.INITIAL_DRAFT, draft = (1..6).map(::rental)),
            sent::add,
        )

        assertTrue(controller.abandon())
        assertTrue(sent.single() is FactoryPlayIntent.Abandon)
    }

    private fun view(
        phase: FactoryPlayPhase,
        draft: List<FactoryRentalSet> = emptyList(),
        team: List<FactoryRentalSet> = emptyList(),
        offers: List<FactorySwapOffer> = emptyList(),
        canRevise: Boolean = false,
    ) = FactoryPlayView(
        playerId = playerId,
        phase = phase,
        format = if (phase == FactoryPlayPhase.AVAILABLE) null else FactoryBattleFormat.SINGLE,
        levelMode = if (phase == FactoryPlayPhase.AVAILABLE) null else FactoryLevelMode.LEVEL_50,
        wins = 0,
        rentAndTradeCount = 0,
        teamSets = team,
        draftSets = draft,
        swapOffers = offers,
        canReviseSelection = canRevise,
    )

    private fun rental(index: Int) = FactoryRentalSet(
        setId = "set$index",
        speciesId = "cobblemon:species$index",
        moveIds = listOf("cobblemon:move$index"),
        abilityId = "cobblemon:ability$index",
        heldItemId = "cobblemon:item$index",
        natureId = "cobblemon:hardy",
        ivs = FactoryStatSpread(31, 31, 31, 31, 31, 31),
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}
