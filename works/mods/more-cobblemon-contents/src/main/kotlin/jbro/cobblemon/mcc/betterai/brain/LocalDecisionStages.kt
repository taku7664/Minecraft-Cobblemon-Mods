package jbro.cobblemon.mcc.betterai.brain

/**
 * Every candidate's value at each step of a searchless decision, keyed by action id, for diagnosing why an action
 * was or was not chosen. Handed to [LocalTacticalBrain]'s stage trace; the game never sets one.
 */
internal data class LocalDecisionStages(
    val turn: Int,
    /** The one-turn ranking's value. */
    val base: Map<String, Double>,
    /** The ranking's tier (publicly failing actions sit low). */
    val tiers: Map<String, Int>,
    /** The engine's adjustment to the status moves the ranking could not price (LocalNativeStatusValue). */
    val nativeStatus: Map<String, Double>,
    /** Engine pricing status: priced, not_played, error_*, or null when it did not run. */
    val nativeStatusStatus: String?,
    /** Each rule's adjustment by name: switch, gimmick, prediction, setup, protect, healRace, screen. */
    val rules: Map<String, Map<String, Double>>,
    val afterRules: Map<String, Double>,
    val afterKindWeights: Map<String, Double>,
    /** After LocalRootDecisionPolicy.refine: the values the draw sees. */
    val refined: Map<String, Double>,
    /** Rule exclusions with their reason (setup gate, status triage, public failure, switching rules). */
    val ruleExclusions: Map<String, String>,
    val drawProbabilities: Map<String, Double>,
    val drawExclusions: Map<String, String>,
    val drawn: String,
    val selected: String,
    /** The inner values the decision computed, by reading ([LocalDecisionInternals]): worths, knockout prices, heal values. */
    val internals: Map<String, Map<String, Double>> = emptyMap(),
)
