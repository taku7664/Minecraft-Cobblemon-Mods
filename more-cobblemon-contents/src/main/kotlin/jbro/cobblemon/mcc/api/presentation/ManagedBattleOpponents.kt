package jbro.cobblemon.mcc.api.presentation

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.network.chat.Component

/**
 * The trainer each recent MCC battle was fought against, kept so a content can name the opponent in its result
 * notice after the battle is gone. The engine fills it when a battle starts; only the latest battles are kept.
 */
object ManagedBattleOpponents {
    private const val KEPT = 512
    private val names = object : LinkedHashMap<UUID, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, String>?): Boolean = size > KEPT
    }

    @Synchronized
    fun remember(battleId: UUID, nameKey: String) {
        names[battleId] = nameKey
    }

    /** The opponent of [battleId], or a generic "opposing trainer" when the battle is not known. */
    @Synchronized
    fun name(battleId: UUID): Component =
        names[battleId]?.let(Component::translatable)
            ?: Component.translatable("message.${MoreCobblemonContents.MOD_ID}.battle_result.opponent")
}
