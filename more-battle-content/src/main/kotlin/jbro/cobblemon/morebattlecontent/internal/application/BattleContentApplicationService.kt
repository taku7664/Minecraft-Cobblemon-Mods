package jbro.cobblemon.morebattlecontent.internal.application

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent

@JvmInline
internal value class BattleContentId(val value: String) : Comparable<BattleContentId> {
    init {
        require(APPLICATION_ID.matches(value)) { "Invalid battle content ID: $value" }
    }

    override fun compareTo(other: BattleContentId): Int = value.compareTo(other.value)
}

@JvmInline
internal value class BattleFormatId(val value: String) : Comparable<BattleFormatId> {
    init {
        require(APPLICATION_ID.matches(value)) { "Invalid battle format ID: $value" }
    }

    override fun compareTo(other: BattleFormatId): Int = value.compareTo(other.value)
}

internal enum class BattleEntryPoint { COMMAND, VERIFIED_TERMINAL }

internal data class BattleApplicationRequestContext(
    val requestId: UUID,
    val playerId: UUID,
    val entryPoint: BattleEntryPoint,
    val entryContextId: UUID? = null,
) {
    init {
        require((entryPoint == BattleEntryPoint.COMMAND) == (entryContextId == null)) {
            "Only a verified terminal entry point may carry an entry context ID"
        }
    }
}

internal class BattleContentDescriptor(
    val contentId: BattleContentId,
    formats: Collection<BattleFormatId>,
) {
    val formats: List<BattleFormatId> = formats.distinct().sorted()

    init {
        require(formats.isNotEmpty()) { "Battle content must support at least one format" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is BattleContentDescriptor && contentId == other.contentId && formats == other.formats

    override fun hashCode(): Int = 31 * contentId.hashCode() + formats.hashCode()

    override fun toString(): String = "BattleContentDescriptor(contentId=$contentId, formats=$formats)"
}

internal class BattleHubView(contents: Collection<BattleContentDescriptor>) {
    val contents: List<BattleContentDescriptor> = contents.toList()

    init {
        require(this.contents == this.contents.sortedBy { it.contentId }) { "Battle Hub contents must be sorted" }
    }

    override fun equals(other: Any?): Boolean = this === other || other is BattleHubView && contents == other.contents

    override fun hashCode(): Int = contents.hashCode()

    override fun toString(): String = "BattleHubView(contents=$contents)"
}

internal enum class BattleContentPhase { AVAILABLE, PREPARING, ACTIVE, SUSPENDED }

internal class BattleContentStatus(
    val playerId: UUID,
    val contentId: BattleContentId,
    val formatId: BattleFormatId?,
    val phase: BattleContentPhase,
    progress: Map<String, Long> = emptyMap(),
) {
    val progress: Map<String, Long> = progress.toMap()

    init {
        require(this.progress.keys.all(APPLICATION_ID::matches)) { "Invalid battle content progress ID" }
        require(this.progress.values.all { it >= 0 }) { "Battle content progress must be non-negative" }
    }

    fun copy(
        playerId: UUID = this.playerId,
        contentId: BattleContentId = this.contentId,
        formatId: BattleFormatId? = this.formatId,
        phase: BattleContentPhase = this.phase,
        progress: Map<String, Long> = this.progress,
    ): BattleContentStatus = BattleContentStatus(playerId, contentId, formatId, phase, progress)

    override fun equals(other: Any?): Boolean = this === other || other is BattleContentStatus &&
        playerId == other.playerId && contentId == other.contentId && formatId == other.formatId &&
        phase == other.phase && progress == other.progress

    override fun hashCode(): Int {
        var result = playerId.hashCode()
        result = 31 * result + contentId.hashCode()
        result = 31 * result + (formatId?.hashCode() ?: 0)
        result = 31 * result + phase.hashCode()
        return 31 * result + progress.hashCode()
    }

    override fun toString(): String =
        "BattleContentStatus(playerId=$playerId, contentId=$contentId, formatId=$formatId, phase=$phase, progress=$progress)"
}

internal enum class BattleApplicationError { UNKNOWN_CONTENT, UNSUPPORTED_FORMAT, CONTENT_FAILURE }

internal sealed interface BattleApplicationResult<out T> {
    data class Success<T>(val value: T) : BattleApplicationResult<T>

    data class Rejected(val error: BattleApplicationError) : BattleApplicationResult<Nothing>
}

internal interface BattleContentApplication {
    val descriptor: BattleContentDescriptor

    fun status(context: BattleApplicationRequestContext): BattleContentStatus

    fun start(context: BattleApplicationRequestContext, formatId: BattleFormatId): BattleContentStatus

    fun resume(context: BattleApplicationRequestContext): BattleContentStatus

    fun abandon(context: BattleApplicationRequestContext): BattleContentStatus
}

internal class DefaultBattleContentApplicationService(
    contentApplications: Collection<BattleContentApplication>,
    private val reportFailure: (BattleContentId, RuntimeException) -> Unit = { contentId, exception ->
        MoreBattleContent.LOGGER.error("Battle content operation failed for ${contentId.value}", exception)
    },
) {
    private val contents = LinkedHashMap<BattleContentId, BattleContentApplication>()

    init {
        contentApplications.forEach(::register)
    }

    @Synchronized
    fun register(content: BattleContentApplication) {
        val contentId = content.descriptor.contentId
        require(contents.putIfAbsent(contentId, content) == null) { "Duplicate battle content ID: ${contentId.value}" }
    }

    fun open(context: BattleApplicationRequestContext): BattleApplicationResult<BattleHubView> {
        return BattleApplicationResult.Success(
            BattleHubView(descriptors()),
        )
    }

    fun status(
        context: BattleApplicationRequestContext,
        contentId: BattleContentId,
    ): BattleApplicationResult<BattleContentStatus> = route(contentId) { it.status(context) }

    fun start(
        context: BattleApplicationRequestContext,
        contentId: BattleContentId,
        formatId: BattleFormatId,
    ): BattleApplicationResult<BattleContentStatus> {
        val content = find(contentId) ?: return BattleApplicationResult.Rejected(BattleApplicationError.UNKNOWN_CONTENT)
        if (formatId !in content.descriptor.formats) {
            return BattleApplicationResult.Rejected(BattleApplicationError.UNSUPPORTED_FORMAT)
        }
        return execute(contentId) { content.start(context, formatId) }
    }

    fun resume(
        context: BattleApplicationRequestContext,
        contentId: BattleContentId,
    ): BattleApplicationResult<BattleContentStatus> = route(contentId) { it.resume(context) }

    fun abandon(
        context: BattleApplicationRequestContext,
        contentId: BattleContentId,
    ): BattleApplicationResult<BattleContentStatus> = route(contentId) { it.abandon(context) }

    private fun route(
        contentId: BattleContentId,
        operation: (BattleContentApplication) -> BattleContentStatus,
    ): BattleApplicationResult<BattleContentStatus> {
        val content = find(contentId) ?: return BattleApplicationResult.Rejected(BattleApplicationError.UNKNOWN_CONTENT)
        return execute(contentId) { operation(content) }
    }

    @Synchronized
    private fun find(contentId: BattleContentId): BattleContentApplication? = contents[contentId]

    @Synchronized
    private fun descriptors(): List<BattleContentDescriptor> =
        contents.values.map(BattleContentApplication::descriptor).sortedBy { it.contentId }

    private fun execute(
        contentId: BattleContentId,
        operation: () -> BattleContentStatus,
    ): BattleApplicationResult<BattleContentStatus> = try {
        BattleApplicationResult.Success(operation())
    } catch (exception: RuntimeException) {
        reportFailure(contentId, exception)
        BattleApplicationResult.Rejected(BattleApplicationError.CONTENT_FAILURE)
    }
}

private val APPLICATION_ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
