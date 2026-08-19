package jbro.cobblemon.morebattlecontent.internal.application

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BattleContentApplicationServiceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val requestId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val context = BattleApplicationRequestContext(requestId, playerId, BattleEntryPoint.COMMAND)

    @Test
    fun `open returns stable sorted content descriptors without invoking content`() {
        val factory = RecordingContent("battle_factory", setOf("double", "single"))
        val tower = RecordingContent("battle_tower", setOf("single", "double"))
        val service = DefaultBattleContentApplicationService(listOf(tower, factory))

        val result = service.open(context)

        assertEquals(
            BattleApplicationResult.Success(
                BattleHubView(
                    listOf(
                        BattleContentDescriptor(BattleContentId("battle_factory"), listOf(BattleFormatId("double"), BattleFormatId("single"))),
                        BattleContentDescriptor(BattleContentId("battle_tower"), listOf(BattleFormatId("double"), BattleFormatId("single"))),
                    ),
                ),
            ),
            result,
        )
        assertEquals(emptyList<String>(), factory.calls)
        assertEquals(emptyList<String>(), tower.calls)
    }

    @Test
    fun `start validates format then forwards the unchanged request context`() {
        val tower = RecordingContent("battle_tower", setOf("single", "double"))
        val service = DefaultBattleContentApplicationService(listOf(tower))

        val rejected = service.start(context, BattleContentId("battle_tower"), BattleFormatId("triples"))
        val accepted = service.start(context, BattleContentId("battle_tower"), BattleFormatId("double"))

        assertEquals(
            BattleApplicationResult.Rejected(BattleApplicationError.UNSUPPORTED_FORMAT),
            rejected,
        )
        assertEquals(
            BattleApplicationResult.Success(tower.status.copy(formatId = BattleFormatId("double"))),
            accepted,
        )
        assertEquals(listOf("start:double"), tower.calls)
        assertSame(context, tower.lastContext)
    }

    @Test
    fun `status resume and abandon use the same registered content service`() {
        val tower = RecordingContent("battle_tower", setOf("single"))
        val service = DefaultBattleContentApplicationService(listOf(tower))

        assertEquals(BattleApplicationResult.Success(tower.status), service.status(context, BattleContentId("battle_tower")))
        assertEquals(BattleApplicationResult.Success(tower.status), service.resume(context, BattleContentId("battle_tower")))
        assertEquals(BattleApplicationResult.Success(tower.status), service.abandon(context, BattleContentId("battle_tower")))
        assertEquals(listOf("status", "resume", "abandon"), tower.calls)
    }

    @Test
    fun `unknown content and content failures use stable errors`() {
        val failed = RecordingContent("battle_tower", setOf("single"), fail = true)
        val reported = mutableListOf<BattleContentId>()
        val service = DefaultBattleContentApplicationService(listOf(failed)) { contentId, _ -> reported += contentId }

        assertEquals(
            BattleApplicationResult.Rejected(BattleApplicationError.UNKNOWN_CONTENT),
            service.status(context, BattleContentId("battle_factory")),
        )
        assertEquals(
            BattleApplicationResult.Rejected(BattleApplicationError.CONTENT_FAILURE),
            service.status(context, BattleContentId("battle_tower")),
        )
        assertEquals(listOf(BattleContentId("battle_tower")), reported)
    }

    @Test
    fun `duplicate content ids and invalid ids are rejected at construction`() {
        assertThrows<IllegalArgumentException> {
            DefaultBattleContentApplicationService(
                listOf(
                    RecordingContent("battle_tower", setOf("single")),
                    RecordingContent("battle_tower", setOf("double")),
                ),
            )
        }
        assertThrows<IllegalArgumentException> { BattleContentId("Battle Tower") }
        assertThrows<IllegalArgumentException> { BattleFormatId("Single Battle") }
    }

    @Test
    fun `content may be registered after the shared service is created`() {
        val service = DefaultBattleContentApplicationService(emptyList())
        val tower = RecordingContent("battle_tower", setOf("single"))

        service.register(tower)

        assertEquals(BattleApplicationResult.Success(tower.status), service.status(context, BattleContentId("battle_tower")))
        assertThrows<IllegalArgumentException> { service.register(tower) }
    }

    @Test
    fun `hub descriptors and status detach caller owned collections`() {
        val rawFormats = mutableListOf(BattleFormatId("single"))
        val descriptor = BattleContentDescriptor(BattleContentId("battle_tower"), rawFormats)
        rawFormats += BattleFormatId("double")

        val rawContents = mutableListOf(descriptor)
        val hub = BattleHubView(rawContents)
        rawContents.clear()

        val rawProgress = mutableMapOf("rank" to 2L)
        val status = BattleContentStatus(
            playerId,
            BattleContentId("battle_tower"),
            BattleFormatId("single"),
            BattleContentPhase.ACTIVE,
            rawProgress,
        )
        rawProgress["rank"] = 9L

        assertEquals(listOf(BattleFormatId("single")), descriptor.formats)
        assertEquals(listOf(descriptor), hub.contents)
        assertEquals(mapOf("rank" to 2L), status.progress)
    }

    private class RecordingContent(
        contentId: String,
        formats: Set<String>,
        private val fail: Boolean = false,
    ) : BattleContentApplication {
        override val descriptor = BattleContentDescriptor(
            BattleContentId(contentId),
            formats.map(::BattleFormatId),
        )
        val calls = mutableListOf<String>()
        var lastContext: BattleApplicationRequestContext? = null
        val status = BattleContentStatus(
            playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            contentId = descriptor.contentId,
            formatId = null,
            phase = BattleContentPhase.AVAILABLE,
            progress = mapOf("rank" to 3L),
        )

        override fun status(context: BattleApplicationRequestContext): BattleContentStatus = respond("status", context)

        override fun start(context: BattleApplicationRequestContext, formatId: BattleFormatId): BattleContentStatus =
            respond("start:${formatId.value}", context).copy(formatId = formatId)

        override fun resume(context: BattleApplicationRequestContext): BattleContentStatus = respond("resume", context)

        override fun abandon(context: BattleApplicationRequestContext): BattleContentStatus = respond("abandon", context)

        private fun respond(call: String, context: BattleApplicationRequestContext): BattleContentStatus {
            calls += call
            lastContext = context
            if (fail) error("content failed")
            return status
        }
    }
}
