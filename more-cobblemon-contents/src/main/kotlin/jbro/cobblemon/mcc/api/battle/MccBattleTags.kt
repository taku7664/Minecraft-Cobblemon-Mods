package jbro.cobblemon.mcc.api.battle

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds

/**
 * What a battle is, as the players' clients learn when it starts: the content that runs it ([contentId], which is also
 * that content's hub tab id), where in that content it stands ([stage], such as `gym` or `champion`) and whom it is
 * against ([opponentId], such as a gym leader's challenge id). Music, camera or UI mods read it on the client through
 * `MccClientContext`.
 */
data class MccBattleTag(val contentId: String, val stage: String? = null, val opponentId: String? = null) {
    init {
        require(ManagedBattleContentIds.isValid(contentId)) { "contentId must be a lowercase namespaced ID" }
        require(stage == null || isValidPart(stage)) { "Invalid battle stage: $stage" }
        require(opponentId == null || isValidPart(opponentId)) { "Invalid battle opponent: $opponentId" }
    }

    companion object {
        private val PART = Regex("[a-z0-9_.:/-]{1,128}")

        /** Whether [value] can be a [stage] or [opponentId]: lowercase letters, digits and `_.:/-`, 128 at most. */
        @JvmStatic
        fun isValidPart(value: String): Boolean = PART.matches(value)
    }
}

/**
 * Tags battles for the players' clients. MCC tags the battles it runs itself (Tower, Factory, PvP, [ManagedPveBattles])
 * with their content; a content mod tags any other battle it starts, managed or not, by starting it inside [during]:
 *
 * ```
 * MccBattleTags.during(setOf(player.uuid), MccBattleTag(CONTENT, "wild_trainer", trainerId)) {
 *     BattleBuilder.pvn(player, npc, ...)
 * }
 * ```
 *
 * The tag reaches the clients before Cobblemon's first battle packet and is withdrawn when the battle ends.
 */
object MccBattleTags {
    private val window = MccBattleTagWindow()
    private val tags = ConcurrentHashMap<UUID, MccBattleTag>()

    /**
     * Runs [start] on the server thread and gives [tag] to the battle it creates for [players]. A battle created inside
     * a nested call takes the innermost tag.
     */
    @JvmStatic
    fun <T> during(players: Collection<UUID>, tag: MccBattleTag, start: () -> T): T = window.during(players, tag, start)

    /** The tag of a battle in progress on this server. */
    @JvmStatic
    fun of(battleId: UUID): MccBattleTag? = tags[battleId]

    /** The tag waiting for a battle of [actorIds], or [fallback] for a managed battle that brought only its content. */
    internal fun claim(battleId: UUID, actorIds: Set<UUID>, fallback: String?): MccBattleTag? {
        val tag = window.claim(actorIds) ?: fallback?.let(::MccBattleTag) ?: return null
        tags[battleId] = tag
        return tag
    }

    internal fun release(battleId: UUID): MccBattleTag? = tags.remove(battleId)

    internal fun clear() = tags.clear()
}

/** The tag waiting on this thread for the battle a [during] block creates. */
internal class MccBattleTagWindow {
    private class Pending(val players: Set<UUID>, val tag: MccBattleTag)

    private val pending = ThreadLocal<Pending?>()

    fun <T> during(players: Collection<UUID>, tag: MccBattleTag, start: () -> T): T {
        require(players.isNotEmpty()) { "players must not be empty" }
        val outer = pending.get()
        pending.set(Pending(players.toSet(), tag))
        try {
            return start()
        } finally {
            if (outer == null) pending.remove() else pending.set(outer)
        }
    }

    /** The waiting tag when every player it names fights in the battle of [actorIds]. */
    fun claim(actorIds: Set<UUID>): MccBattleTag? = pending.get()?.takeIf { actorIds.containsAll(it.players) }?.tag
}
