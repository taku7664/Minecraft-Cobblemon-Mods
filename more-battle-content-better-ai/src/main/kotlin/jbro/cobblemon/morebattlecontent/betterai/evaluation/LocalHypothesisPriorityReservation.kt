package jbro.cobblemon.morebattlecontent.betterai.evaluation

/** Experimental response selection, always bounded by the hypothesis and overall action caps. */
internal enum class LocalHypothesisPriorityReservation {
    NONE,
    SINGLE,
    /** One per declared-requirements presence group, not per condition, target or priority tier. */
    CONDITION_GROUPS,
}
