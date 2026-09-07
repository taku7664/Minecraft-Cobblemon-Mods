package jbro.cobblemon.morebattlecontent.betterai

/** Public rule calculation only; callers supply currently active, living, publicly known participants. */
internal object EmbeddedPublicPressure {
    data class Participant(val ident: String, val ability: String?, val item: String? = null,
                           val suppressed: Boolean = false)

    fun loss(actor: String, target: String?, pressureTarget: String?, active: List<Participant>,
             preparing: Boolean = false): Int {
        require(active.map { EmbeddedTeamInput.identity(it.ident) }.distinct().size == active.size)
        val gas = active.any { it.ability == "neutralizinggas" && !it.suppressed }
        val foes = active.filter { it.ident.take(2) != actor.take(2) }
        fun exertsPressure(participant: Participant) = participant.ability == "pressure" &&
            !participant.suppressed && (!gas || participant.item == "abilityshield")
        if (preparing && target.isNullOrBlank() && pressureTarget in setOf("normal", "adjacentFoe")) {
            return if (foes.isNotEmpty() && foes.all(::exertsPressure)) 1 else 0
        }
        val targets = when (pressureTarget) {
            "allAdjacentFoes", "allAdjacent", "all" -> foes
            "normal", "adjacentFoe", "any", "randomNormal" ->
                foes.filter { target != null && EmbeddedTeamInput.identity(it.ident) == EmbeddedTeamInput.identity(target) }
            else -> emptyList()
        }
        return targets.count(::exertsPressure)
    }
}
