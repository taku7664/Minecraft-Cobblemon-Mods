package jbro.cobblemon.mcc.client

import io.netty.buffer.Unpooled
import java.util.UUID
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomError
import jbro.cobblemon.mcc.internal.pvp.PvpRoomMutation
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomService
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSide
import jbro.cobblemon.mcc.internal.pvp.PvpRoomView
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.leaveRequestError
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomListStatePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomSummaryView
import jbro.cobblemon.mcc.internal.pvp.network.listStateFor
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionScreenController
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionViewState
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PvP room flows as players live them, on the real room service, the real room list builder and codec, and the
 * client decisions the network receivers call ([PvpRoomNavigationContract]). [SimServer.leave] follows
 * `PvpPlayNetworking.handleRoomIntent`'s leave branch; the battle lounge (teleports, Cobblemon spectating) is out of
 * scope, so a lounge exit is its room effect plus the accepted exit reply.
 */
class PvpRoomUserScenarioTest {
    private val alice = UUID(0, 1)
    private val bob = UUID(0, 2)
    private val carol = UUID(0, 3)
    private val dave = UUID(0, 4)

    @Test
    fun `a seated guest who leaves the lobby stays out even when they press the HUD open key`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connect)
        val roomId = a.create()
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.LEFT)
        c.join(roomId)

        assertNull(b.leave(roomId))

        assertNull(server.rooms.roomFor(bob))
        assertTrue(b.listedRoomIds.contains(roomId), "the room is still public and listed")
        assertFalse(b.hudShowsRoom)
        b.pressHudOpen()
        assertNull(server.rooms.roomFor(bob), "the HUD must not rejoin a room that was left")
        assertNull(a.remembered!!.leftPlayer)
        // The host never sat down, so they watch alongside Carol.
        assertEquals(listOf(alice, carol), a.remembered!!.spectators.map(PvpRoomMemberView::playerId))
    }

    @Test
    fun `the old client rule reproduces the report - leaving then opening the HUD rejoins as a spectator`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connectLegacy)
        val roomId = a.create()
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.LEFT)
        c.join(roomId)

        b.leave(roomId)
        assertTrue(b.hudShowsRoom, "the old rule kept a still-listed room")
        b.pressHudOpen()

        assertTrue(bob in server.rooms.get(roomId)!!.spectatorIds)
    }

    @Test
    fun `going back to the list keeps the room and reopening it leaves a seated player in their seat`() {
        val server = SimServer()
        val (a, b) = listOf(alice, bob).map(server::connect)
        val roomId = a.create()
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.RIGHT)

        b.backToList()
        assertTrue(b.hudShowsRoom)
        b.pressHudOpen()

        assertEquals(bob, server.rooms.get(roomId)!!.rightPlayerId)
        assertFalse(bob in server.rooms.get(roomId)!!.spectatorIds)
    }

    @Test
    fun `a host who leaves hands the room to the earliest remaining member`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connect)
        val roomId = a.create()
        b.join(roomId)
        c.join(roomId)

        assertNull(a.leave(roomId))

        assertEquals(bob, server.rooms.get(roomId)!!.hostId)
        assertEquals(bob, b.remembered!!.hostId)
        assertEquals(bob, c.remembered!!.hostId)
        assertFalse(a.hudShowsRoom)
    }

    @Test
    fun `the last member leaving removes the room from every list`() {
        val server = SimServer()
        val (a, b) = listOf(alice, bob).map(server::connect)
        val roomId = a.create()

        assertNull(a.leave(roomId))

        assertNull(server.rooms.get(roomId))
        assertTrue(a.listedRoomIds.isEmpty())
        assertFalse(a.hudShowsRoom)
        b.backToList()
        assertTrue(b.listedRoomIds.isEmpty())
    }

    @Test
    fun `the leave button is enabled exactly when the server accepts the leave`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connect)
        val roomId = a.create()
        a.sit(roomId, PvpRoomSide.LEFT)
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.RIGHT)
        c.join(roomId)

        for (phase in listOf(PvpRoomPhase.LOBBY, PvpRoomPhase.TEAM_PREVIEW, PvpRoomPhase.ACTIVE)) {
            if (phase == PvpRoomPhase.TEAM_PREVIEW) server.rooms.startPreview(roomId, alice)
            if (phase == PvpRoomPhase.ACTIVE) server.rooms.markActive(roomId)
            val room = server.rooms.get(roomId)!!
            assertEquals(phase, room.phase)
            for (player in listOf(alice, bob, carol)) {
                val button = PvpRoomNavigationContract.canLeave(phase == PvpRoomPhase.LOBBY, player in room.spectatorIds)
                assertEquals(room.leaveRequestError(player) == null, button, "$phase / $player")
            }
        }
        assertEquals(PvpRoomError.INVALID_PHASE, a.leave(roomId))
        assertEquals(alice, server.rooms.get(roomId)!!.leftPlayerId)
    }

    @Test
    fun `a spectator leaving team preview loses the preview and the others see one spectator fewer`() {
        val server = SimServer()
        val (a, b, c, d) = listOf(alice, bob, carol, dave).map(server::connect)
        val roomId = a.create()
        a.sit(roomId, PvpRoomSide.LEFT)
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.RIGHT)
        c.join(roomId)
        d.join(roomId)
        server.push(requireApplied(server.rooms.startPreview(roomId, alice)))
        c.spectatorPreview = true
        d.spectatorPreview = true

        assertNull(c.leave(roomId))

        assertFalse(c.spectatorPreview)
        assertFalse(c.hudShowsRoom)
        assertTrue(d.spectatorPreview)
        assertEquals(listOf(dave), d.remembered!!.spectators.map(PvpRoomMemberView::playerId))
        assertEquals(PvpRoomPhase.TEAM_PREVIEW, server.rooms.get(roomId)!!.phase)
    }

    @Test
    fun `a spectator returning from a running battle is out of the room and stays out`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connect)
        val roomId = a.create()
        a.sit(roomId, PvpRoomSide.LEFT)
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.RIGHT)
        server.rooms.startPreview(roomId, alice)
        server.rooms.markActive(roomId)
        c.join(roomId)
        assertTrue(carol in server.rooms.get(roomId)!!.spectatorIds)

        c.loungeExit()

        assertNull(server.rooms.roomFor(carol))
        assertFalse(c.hudShowsRoom)
        c.pressHudOpen()
        assertNull(server.rooms.roomFor(carol))
        assertEquals(PvpRoomPhase.ACTIVE, server.rooms.get(roomId)!!.phase)
    }

    @Test
    fun `a challenge preview survives a room list outside any room`() {
        val server = SimServer()
        val b = server.connect(bob)
        b.challengePreview = true

        b.backToList()

        assertTrue(b.challengePreview)
    }

    @Test
    fun `after leaving one room a player can join another`() {
        val server = SimServer()
        val (a, b, d) = listOf(alice, bob, dave).map(server::connect)
        val first = a.create()
        val second = d.create()
        b.join(first)

        b.leave(first)

        assertTrue(b.join(second) is PvpRoomMutation.Applied)
        assertEquals(second, b.remembered!!.roomId)
    }

    @Test
    fun `a finished match returns everyone to the lobby in their seats and a spectator can then leave`() {
        val server = SimServer()
        val (a, b, c) = listOf(alice, bob, carol).map(server::connect)
        val roomId = a.create()
        a.sit(roomId, PvpRoomSide.LEFT)
        b.join(roomId)
        b.sit(roomId, PvpRoomSide.RIGHT)
        c.join(roomId)
        server.rooms.startPreview(roomId, alice)
        server.rooms.markActive(roomId)

        server.push(server.rooms.finishMatch(roomId)!!)

        val room = server.rooms.get(roomId)!!
        assertEquals(PvpRoomPhase.LOBBY, room.phase)
        assertEquals(alice to bob, room.leftPlayerId to room.rightPlayerId)
        assertEquals(PvpRoomPhase.LOBBY, c.remembered!!.phase)
        assertNull(c.leave(roomId))
        assertFalse(c.hudShowsRoom)
    }

    @Test
    fun `a one pokemon party picks its only pokemon and can confirm`() {
        val own = PvpSelectionPartySlot(UUID(9, 1), "cobblemon:pikachu", null, 30, 50)
        val state = PvpSelectionViewState(
            matchId = UUID(9, 0),
            format = PvpBattleFormat.DOUBLE,
            opponentName = "Bob",
            ownParty = listOf(own),
            opponentParty = listOf(PvpSelectionOpponentSlot("cobblemon:eevee"), PvpSelectionOpponentSlot("cobblemon:mew")),
            selectedPokemonIds = emptyList(),
            selectionDeadlineEpochMillis = 0,
            waitingForOpponent = false,
        )
        val sent = ArrayList<Any>()
        val controller = PvpSelectionScreenController(state, send = { sent.add(it) })

        assertEquals(1, state.requiredSelectionSize)
        assertFalse(controller.submit())
        assertTrue(controller.toggle(own.pokemonId))
        assertTrue(controller.submit())
        assertEquals(1, sent.size)
    }

    private fun requireApplied(mutation: PvpRoomMutation): PvpRoomView = (mutation as PvpRoomMutation.Applied).room

    /** The server's room state and the replies `PvpPlayNetworking` sends for it. */
    private class SimServer {
        val rooms = PvpRoomService()
        private val clients = LinkedHashMap<UUID, SimClient>()

        fun connect(playerId: UUID): SimClient = SimClient(playerId, this, legacyListRule = false).also { clients[playerId] = it }

        /** A client with the room list rule from before the fix. */
        fun connectLegacy(playerId: UUID): SimClient = SimClient(playerId, this, legacyListRule = true).also { clients[playerId] = it }

        /** `pushRoomToMembers`. */
        fun push(room: PvpRoomView) = room.memberIds.forEach { clients[it]?.receiveRoom(clientView(room)) }

        /** `sendRoomList`. */
        fun sendList(playerId: UUID, requestId: UUID?) =
            clients.getValue(playerId).receiveList(rooms.listStateFor(playerId, requestId, ::summary))

        fun create(playerId: UUID): UUID {
            val room = rooms.create(playerId, PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.SINGLE, setOf(PvpBattleMechanic.MEGA))).room
            clients.getValue(playerId).receiveRoom(clientView(room))
            return room.roomId
        }

        fun join(playerId: UUID, roomId: UUID): PvpRoomMutation =
            rooms.join(roomId, playerId).also { if (it is PvpRoomMutation.Applied) push(it.room) }

        fun sit(playerId: UUID, roomId: UUID, side: PvpRoomSide) = push((rooms.claimSeat(roomId, playerId, side) as PvpRoomMutation.Applied).room)

        /** `handleRoomIntent`'s Leave outside a running battle: refuse, or leave, update the rest and reply with the list. */
        fun leave(playerId: UUID, roomId: UUID): PvpRoomError? {
            val current = rooms.get(roomId) ?: return PvpRoomError.NOT_MEMBER
            current.leaveRequestError(playerId)?.let { return it }
            check(current.phase != PvpRoomPhase.ACTIVE) { "a running battle's spectator leaves through the lounge exit" }
            rooms.leave(roomId, playerId)?.let(::push)
            sendList(playerId, UUID.randomUUID())
            return null
        }

        /** `exitSpectator` through the lounge's return control: the room effect and the accepted exit reply. */
        fun loungeExit(playerId: UUID) {
            val room = requireNotNull(rooms.roomFor(playerId))
            check(playerId in room.spectatorIds)
            rooms.leave(room.roomId, playerId)?.let(::push)
            clients.getValue(playerId).loungeExitAccepted()
        }

        private fun member(playerId: UUID) = PvpRoomMemberView(playerId, "player-${playerId.leastSignificantBits}")

        private fun summary(room: PvpRoomView) = PvpRoomSummaryView(
            room.roomId, member(room.hostId), room.settings, room.phase,
            room.leftPlayerId?.let(::member), room.rightPlayerId?.let(::member), room.spectatorIds.size,
        )

        private fun clientView(room: PvpRoomView) = PvpRoomClientView(
            room.roomId, room.hostId, room.settings, room.phase,
            room.leftPlayerId?.let(::member), room.rightPlayerId?.let(::member), room.spectatorIds.map(::member), emptyList(),
        )
    }

    /** One player's client: what `PvpPlayClientNetworking` and `PvpHubClient` keep, and the controls they press. */
    private class SimClient(val playerId: UUID, private val server: SimServer, private val legacyListRule: Boolean) {
        var remembered: PvpRoomClientView? = null
            private set
        var listedRoomIds: List<UUID> = emptyList()
            private set
        var spectatorPreview = false
        var challengePreview = false

        val hudShowsRoom: Boolean
            get() = remembered != null

        fun create(): UUID = server.create(playerId)
        fun join(roomId: UUID): PvpRoomMutation = server.join(playerId, roomId)
        fun sit(roomId: UUID, side: PvpRoomSide) = server.sit(playerId, roomId, side)
        fun leave(roomId: UUID): PvpRoomError? = server.leave(playerId, roomId)
        fun loungeExit() = server.loungeExit(playerId)

        /** "방 목록": a refresh request; the membership stays. */
        fun backToList() = server.sendList(playerId, UUID.randomUUID())

        /** The HUD's open control: `showLastRoom`, whose tab then sends the join handshake for the cached room. */
        fun pressHudOpen() {
            val room = remembered ?: return
            server.join(playerId, PvpRoomNavigationContract.openIntent(UUID.randomUUID(), room.roomId).roomId)
        }

        /** `PvpHubClient.acceptRoom`. */
        fun receiveRoom(view: PvpRoomClientView) {
            remembered = view
        }

        /** The room list receiver and `PvpHubClient.acceptRooms`, after a trip through the real codec. */
        fun receiveList(sent: PvpRoomListStatePayload) {
            val payload = roundTrip(sent)
            listedRoomIds = payload.rooms.map(PvpRoomSummaryView::roomId)
            remembered = if (legacyListRule) {
                remembered?.takeIf { kept -> payload.rooms.any { it.roomId == kept.roomId } }
            } else {
                PvpRoomNavigationContract.rememberedAfterRoomList(remembered, payload.memberRoomId)
            }
            if (spectatorPreview) spectatorPreview = PvpRoomNavigationContract.keepsSelectionAfterRoomList(true, payload.memberRoomId)
            if (challengePreview) challengePreview = PvpRoomNavigationContract.keepsSelectionAfterRoomList(false, payload.memberRoomId)
        }

        /** The accepted lounge exit: `PvpHubClient.leftRoom`. */
        fun loungeExitAccepted() {
            remembered = null
            if (spectatorPreview) spectatorPreview = PvpRoomNavigationContract.keepsSelectionAfterRoomList(true, null)
        }

        private fun roundTrip(payload: PvpRoomListStatePayload): PvpRoomListStatePayload {
            val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
            PvpRoomListStatePayload.CODEC.encode(buffer, payload)
            return PvpRoomListStatePayload.CODEC.decode(buffer)
        }
    }
}
