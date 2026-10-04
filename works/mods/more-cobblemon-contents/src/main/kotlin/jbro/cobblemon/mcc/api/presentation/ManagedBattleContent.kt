package jbro.cobblemon.mcc.api.presentation

import java.util.UUID
import jbro.cobblemon.mcc.api.battle.MccBattleTag
import java.util.concurrent.ConcurrentHashMap

object ManagedBattleContentIds {
    const val BATTLE_TOWER: String = "more_cobblemon_contents:battle_tower"
    const val BATTLE_FACTORY: String = "more_cobblemon_contents:battle_factory"
    const val PVP: String = "more_cobblemon_contents:pvp"
    const val LEAGUE_CHALLENGE: String = "more_cobblemon_contents:league_challenge"
    const val AI_TEST: String = "more_cobblemon_contents:ai_test"

    private val CONTENT_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

    @JvmStatic
    fun isValid(value: String): Boolean = CONTENT_ID.matches(value)
}

/** The tags the server sent for the battles this client is in or watching. */
class ManagedBattleContentClientState {
    private val tags = ConcurrentHashMap<UUID, MccBattleTag>()

    fun show(battleId: UUID, tag: MccBattleTag) {
        tags[battleId] = tag
    }

    fun show(battleId: UUID, contentId: String) = show(battleId, MccBattleTag(contentId))

    fun hide(battleId: UUID) {
        tags.remove(battleId)
    }

    fun clear() {
        tags.clear()
    }

    fun tag(battleId: UUID): MccBattleTag? = tags[battleId]

    fun contentId(battleId: UUID): String? = tags[battleId]?.contentId
}

/** Client side: what each battle is. `MccClientContext` combines it with the battle on screen. */
object ManagedBattleContentClient {
    private val state = ManagedBattleContentClientState()

    /** The content running [battleId]. Better Cobblemon Music looks this up by reflection, so keep its signature. */
    @JvmStatic
    fun contentId(battleId: UUID): String? = state.contentId(battleId)

    /** The full tag of [battleId]: content, stage and opponent. */
    @JvmStatic
    fun tag(battleId: UUID): MccBattleTag? = state.tag(battleId)

    internal fun show(battleId: UUID, tag: MccBattleTag) = state.show(battleId, tag)

    internal fun hide(battleId: UUID) = state.hide(battleId)

    internal fun clear() = state.clear()
}
