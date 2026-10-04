package jbro.cobblemon.mcc.api.client

import java.util.UUID

/**
 * A battle the server has approved and holds while the client plays its entry transition; it opens once the screen is
 * covered. Music mods read it to start the battle's track with the transition instead of after it.
 *
 * @property style `wild`, `legendary` or `trainer`.
 * @property species the opponent's lead (the wild Pokémon, or the trainer's first), as `namespace:path`, else null.
 * @property form the lead's form name, empty for the standard form.
 * @property labels the lead's marks among `alpha`, `legendary` and `ultra_beast`.
 */
data class MccPendingBattle(
    val battleId: UUID,
    val style: String,
    val species: String?,
    val form: String,
    val labels: Set<String>,
)

/** The battle waiting behind the entry transition on this client, if any. Read on the client thread. */
object MccPendingBattles {
    @Volatile
    private var pending: MccPendingBattle? = null

    @JvmStatic
    fun current(): MccPendingBattle? = pending

    internal fun set(battle: MccPendingBattle?) {
        pending = battle
    }
}
