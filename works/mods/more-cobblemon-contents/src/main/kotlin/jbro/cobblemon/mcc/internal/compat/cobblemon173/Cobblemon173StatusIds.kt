package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Cobblemon's status name as the Showdown id every rule reads: `cobblemon:poisonbadly` is `tox`, `cobblemon:burn` is
 * `brn`. The AI's sets know the long spellings only in places, so a live badly poisoned Pokemon read as taking no
 * residual damage. Unknown names pass through canonicalised.
 */
internal fun cobblemonStatusToShowdown(name: String?): String? {
    val id = name?.let(PublicIds::canonical)?.takeIf(String::isNotEmpty) ?: return null
    return STATUS_IDS[id] ?: id
}

private val STATUS_IDS = mapOf(
    "burn" to "brn",
    "poison" to "psn",
    "poisonbadly" to "tox",
    "paralysis" to "par",
    "sleep" to "slp",
    "frozen" to "frz",
    "freeze" to "frz",
)
