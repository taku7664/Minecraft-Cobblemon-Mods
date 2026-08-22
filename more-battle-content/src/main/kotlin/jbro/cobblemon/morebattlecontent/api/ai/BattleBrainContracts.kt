package jbro.cobblemon.morebattlecontent.api.ai

import java.util.UUID
import java.util.Collections
import java.util.concurrent.CompletionStage

data class BrainId(val value: String) {
    init {
        require(VALID_ID.matches(value)) {
            "Brain id must be a lowercase namespaced id"
        }
    }

    override fun toString(): String = value

    private companion object {
        val VALID_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

enum class BattleFormat { SINGLE, DOUBLE }

enum class BrainCapability {
    SINGLE,
    DOUBLE;

    fun supports(format: BattleFormat): Boolean = name == format.name
}

enum class BattleKnowledgePolicy { FAIR_INFERENCE }
enum class BattleSide { ALLY, OPPONENT }
enum class BattleActionKind { USE_MOVE, SWITCH, FORFEIT, WAIT, COMPOSITE }

enum class BattleStrategyObjective {
    BALANCED_PRESSURE,
    FIELD_CONTROL,
    SPEED_CONTROL,
    STATUS_PRESSURE,
    PIVOTING,
    SPREAD_PRESSURE,
    FOCUS_FIRE,
    SETUP_SWEEP,
    PRESERVE_CORE,
}

enum class BattleTeamRole {
    ACE,
    SETUP_ENABLER,
    WEAKNESS_COVER,
    WALLBREAKER,
    CLEANER,
    PIVOT,
    SPEED_CONTROL,
    FIELD_SUPPORT,
    DISRUPTOR,
    GENERALIST,
}

class BattleTeamMemberPlan(
    val speciesId: String,
    roles: Set<BattleTeamRole>,
    val tacticalSummary: String,
    preferredMoveIds: Set<String> = emptySet(),
    val leadPriority: Int = 0,
    val preservationPriority: Int = 0,
) {
    val roles: Set<BattleTeamRole> = Collections.unmodifiableSet(LinkedHashSet(roles))
    val preferredMoveIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(preferredMoveIds))

    init {
        require(RESOURCE_ID.matches(speciesId)) { "Invalid strategy member species ID" }
        require(this.roles.isNotEmpty()) { "Strategy member requires at least one role" }
        require(tacticalSummary.isNotBlank() && tacticalSummary.length <= MAX_TACTICAL_SUMMARY_LENGTH) {
            "Strategy member summary must contain 1 to $MAX_TACTICAL_SUMMARY_LENGTH characters"
        }
        require(this.preferredMoveIds.all(RESOURCE_ID::matches)) { "Invalid member preferred move ID" }
        require(leadPriority in PRIORITY_RANGE) { "Strategy lead priority must be between 0 and 100" }
        require(preservationPriority in PRIORITY_RANGE) {
            "Strategy preservation priority must be between 0 and 100"
        }
    }

    private companion object {
        const val MAX_TACTICAL_SUMMARY_LENGTH = 256
        val PRIORITY_RANGE = 0..100
        val RESOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

class BattleStrategyBrief(
    val strategyId: String,
    val displayNameKey: String,
    val descriptionKey: String,
    val aiSummary: String,
    objectives: Set<BattleStrategyObjective>,
    members: List<BattleTeamMemberPlan> = emptyList(),
) {
    val objectives: Set<BattleStrategyObjective> = Collections.unmodifiableSet(LinkedHashSet(objectives))
    val members: List<BattleTeamMemberPlan> = Collections.unmodifiableList(ArrayList(members))

    init {
        require(STRATEGY_ID.matches(strategyId)) { "Strategy ID must be a lowercase namespaced ID" }
        require(displayNameKey.isNotBlank()) { "Strategy display name key cannot be blank" }
        require(descriptionKey.isNotBlank()) { "Strategy description key cannot be blank" }
        require(aiSummary.isNotBlank() && aiSummary.length <= MAX_AI_SUMMARY_LENGTH) {
            "Strategy AI summary must contain 1 to $MAX_AI_SUMMARY_LENGTH characters"
        }
        require(this.objectives.isNotEmpty()) { "Strategy requires at least one objective" }
        require(this.members.size <= MAX_TEAM_MEMBERS) { "Strategy cannot describe more than $MAX_TEAM_MEMBERS members" }
        require(this.members.map(BattleTeamMemberPlan::speciesId).distinct().size == this.members.size) {
            "Strategy members must have unique species"
        }
    }

    private companion object {
        const val MAX_AI_SUMMARY_LENGTH = 512
        const val MAX_TEAM_MEMBERS = 6
        val STRATEGY_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

class BattlePokemonStateView(
    val battlePokemonId: UUID,
    val side: BattleSide,
    val activeSlot: Int?,
    val speciesId: String,
    val formId: String?,
    val level: Int?,
    val hpFraction: Double,
    val statusId: String?,
    statStages: Map<String, Int>,
    knownMoveIds: Set<String>,
    val knownAbilityId: String?,
    val knownHeldItemId: String?,
    val fainted: Boolean,
    knownTypeIds: Set<String> = emptySet(),
    val combatStats: BattleCombatStatRangesView? = null,
    knownFormStates: Map<String, BattlePokemonFormStateView> = emptyMap(),
    val actionConstraints: BattlePokemonActionConstraintView = BattlePokemonActionConstraintView.empty(),
) {
    val statStages: Map<String, Int> = statStages.toMap()
    val knownMoveIds: Set<String> = knownMoveIds.toSet()
    val knownTypeIds: Set<String> = knownTypeIds.toSet()
    val knownFormStates: Map<String, BattlePokemonFormStateView> = knownFormStates.toMap()

    init {
        require(activeSlot == null || activeSlot >= 0)
        require(level == null || level > 0)
        require(hpFraction in 0.0..1.0)
        require(speciesId.isNotBlank())
        require(this.knownTypeIds.all { it.isNotBlank() })
        require(this.knownFormStates.keys.all { it.isNotBlank() })
    }
}

/** Public volatile effects that constrain which action the active Pokemon may take. */
data class BattlePokemonActionConstraintView @JvmOverloads constructor(
    val taunted: Boolean = false,
    val encoreMoveId: String? = null,
    val trapped: Boolean = false,
    val mustRecharge: Boolean = false,
) {
    init {
        require(encoreMoveId == null || encoreMoveId.isNotBlank())
    }

    companion object {
        private val EMPTY = BattlePokemonActionConstraintView()

        @JvmStatic
        fun empty(): BattlePokemonActionConstraintView = EMPTY
    }
}

class BattlePokemonFormStateView(
    val formId: String,
    knownTypeIds: Set<String>,
    val combatStats: BattleCombatStatRangesView,
) {
    val knownTypeIds: Set<String> = knownTypeIds.toSet()

    init {
        require(formId.isNotBlank())
        require(this.knownTypeIds.all { it.isNotBlank() })
    }
}

class BattleStateView(
    val battleId: UUID,
    val format: BattleFormat,
    val turn: Int,
    pokemon: List<BattlePokemonStateView>,
    val field: BattleFieldStateView,
    remainingPokemonBySide: Map<BattleSide, Int>,
    observedEvents: List<BattleObservedEventView>,
    inferences: List<BattleInferenceView>,
) {
    val pokemon: List<BattlePokemonStateView> = pokemon.toList()
    val remainingPokemonBySide: Map<BattleSide, Int> = remainingPokemonBySide.toMap()
    val observedEvents: List<BattleObservedEventView> = observedEvents.toList()
    val inferences: List<BattleInferenceView> = inferences.toList()

    init {
        require(turn >= 0)
        val pokemonIds = pokemon.map { it.battlePokemonId }.toSet()
        require(pokemonIds.size == pokemon.size) {
            "Battle state cannot contain duplicate Pokemon identities"
        }
        require(remainingPokemonBySide.keys == BattleSide.entries.toSet()) {
            "Battle state must report remaining Pokemon for both sides"
        }
        require(remainingPokemonBySide.values.all { it >= 0 })
        require(observedEvents.zipWithNext().all { (before, after) -> before.sequence < after.sequence }) {
            "Public observations must be strictly ordered by sequence"
        }
        require(observedEvents.all { it.turn <= turn }) {
            "Public observations cannot come from a future turn"
        }
        require(observedEvents.all { event ->
            (event.actorPokemonId == null || event.actorPokemonId in pokemonIds) &&
                event.targetPokemonIds.all { it in pokemonIds }
        }) { "Public observations must reference Pokemon in the state view" }
        require(inferences.all { it.subjectPokemonId in pokemonIds }) {
            "Inferences must reference Pokemon in the state view"
        }
        require(pokemon.none {
            it.side == BattleSide.OPPONENT &&
                (it.combatStats?.knowledge == BattleCombatStatKnowledge.EXACT_OWN ||
                    it.knownFormStates.values.any { form ->
                        form.combatStats.knowledge == BattleCombatStatKnowledge.EXACT_OWN
                    })
        }) { "Opponent combat stats cannot cross the public boundary as exact own knowledge" }
        BattleSide.entries.forEach { side ->
            val activeSlots = pokemon.filter { it.side == side }.mapNotNull { it.activeSlot }
            require(activeSlots.distinct().size == activeSlots.size) {
                "A battle side cannot expose duplicate active slots"
            }
            val activeLimit = if (format == BattleFormat.SINGLE) 1 else 2
            require(activeSlots.size <= activeLimit) {
                "$format cannot expose ${activeSlots.size} active Pokemon on one side"
            }
        }
    }
}

data class BattleTargetSlot(val side: BattleSide, val slot: Int) {
    init {
        require(slot >= 0)
    }
}

data class BattleMechanicCandidate(
    val mechanicId: String,
    val target: BattleTargetSlot?,
    val publicCost: Int?,
    val transformedMoveId: String? = null,
) {
    init {
        require(mechanicId.isNotBlank())
        require(publicCost == null || publicCost >= 0)
        require(transformedMoveId == null || transformedMoveId.isNotBlank())
    }
}

enum class BattleMoveDamageCategory { PHYSICAL, SPECIAL, STATUS }

enum class BattleMoveTargetPattern {
    SELECTED,
    SELF,
    ALL_ACTIVE,
    ALL_ADJACENT,
    ALL_OPPONENTS,
    ALL_ALLIES,
    SIDE,
    SCRIPTED,
}

enum class BattleMoveEffectCoverage { DECLARATIVE_PARTIAL }

enum class BattleMoveEffectKind {
    HEAL_FRACTION,
    DRAIN_FRACTION,
    RECOIL_FRACTION,
    STATUS,
    VOLATILE_STATUS,
    STAT_STAGE,
    SWITCH_USER,
    SWITCH_TARGET,
    PROTECT_USER,
    SIDE_CONDITION,
    FIELD_CONDITION,
    WEATHER,
    TERRAIN,
    MULTI_HIT,
    FIXED_DAMAGE_LEVEL,
    FIXED_DAMAGE_VALUE,
    SELF_DESTRUCT,
    CHARGE_TURN,
    RECHARGE_TURN,
    FIRST_ACTIVE_TURN_ONLY,
    SLOT_CONDITION,
    ONE_HIT_KO,
    CRASH_RECOIL,
    MAX_HP_RECOIL,
    STRUGGLE_RECOIL,
    BREAKS_PROTECTION,
    ALWAYS_CRITICAL,
    STEALS_STAT_STAGES,
    THAWS_TARGET,
    USABLE_WHILE_ASLEEP,
    MULTI_ACCURACY,
    IGNORE_ABILITY,
    IGNORE_DEFENSIVE_STAGES,
    IGNORE_EVASION_STAGES,
    IGNORE_TYPE_IMMUNITY,
    CHARGE_SKIP_WEATHER,
}

enum class BattleMoveEffectTarget { USER, SELECTED_TARGET, USER_SIDE, TARGET_SIDE, FIELD }

enum class BattleMoveRequirementKind {
    WEATHER_ANY_OF,
    TERRAIN_PRESENT,
    USER_STATUS_ANY_OF,
    USER_STATUS_PRESENT,
    TARGET_STATUS_ANY_OF,
    TARGET_STATUS_ABSENT,
    USER_TYPE_ANY_OF,
    USER_HP_ABOVE_FRACTION,
    TARGET_HP_ABOVE_USER,
    USER_HELD_BERRY,
    TARGET_HELD_ITEM_PRESENT,
    FAINTED_ALLY_PRESENT,
    RESERVE_ALLY_PRESENT,
    PRIOR_DAMAGE_THIS_TURN,
    OTHER_MOVES_USED,
    USER_VOLATILE_PRESENT,
    TARGET_LAST_MOVE_PRESENT,
    TARGET_PENDING_DAMAGING_MOVE,
    USER_SPECIES_ANY_OF,
    MULTIPLE_ACTIVE_POKEMON,
    OPPOSITE_GENDER,
}

class BattleMoveRequirementView(
    val kind: BattleMoveRequirementKind,
    acceptedValueIds: Set<String> = emptySet(),
    val threshold: Double? = null,
) {
    val acceptedValueIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(acceptedValueIds))

    init {
        require(this.acceptedValueIds.all { it.isNotBlank() }) {
            "Move requirement accepted values cannot be blank"
        }
        require(threshold == null || threshold.isFinite() && threshold in 0.0..1.0) {
            "Move requirement threshold must be between 0 and 1"
        }
    }
}

class BattleMoveEffectView(
    val kind: BattleMoveEffectKind,
    val target: BattleMoveEffectTarget,
    val probability: Double? = null,
    val valueId: String? = null,
    val fractionRange: BattleFractionRange? = null,
    val amountRange: BattleIntegerRange? = null,
    statStages: Map<String, Int> = emptyMap(),
) {
    val statStages: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(statStages))

    init {
        require(probability == null || probability.isFinite() && probability in 0.0..1.0) {
            "Move effect probability must be between 0 and 1"
        }
        require(valueId == null || valueId.isNotBlank()) { "Move effect value ID cannot be blank" }
        require(this.statStages.keys.all { it.isNotBlank() }) { "Move effect stat IDs cannot be blank" }
        require(this.statStages.values.all { it in -6..6 && it != 0 }) {
            "Move effect stat stages must be non-zero values between -6 and 6"
        }
    }
}

class BattleMoveEffectsView @JvmOverloads constructor(
    val coverage: BattleMoveEffectCoverage,
    effects: List<BattleMoveEffectView>,
    val scriptedBehavior: Boolean,
    requirements: List<BattleMoveRequirementView> = emptyList(),
    mechanicFlags: Set<String> = emptySet(),
) {
    val effects: List<BattleMoveEffectView> = Collections.unmodifiableList(ArrayList(effects))
    val requirements: List<BattleMoveRequirementView> = Collections.unmodifiableList(ArrayList(requirements))
    val mechanicFlags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(mechanicFlags))

    init {
        require(this.mechanicFlags.all { it.isNotBlank() }) { "Move mechanic flags cannot be blank" }
    }
}

data class BattleMoveCandidateView @JvmOverloads constructor(
    val typeId: String,
    val damageCategory: BattleMoveDamageCategory,
    val power: Double,
    val accuracy: Double,
    val priority: Int,
    val currentPp: Int,
    val targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED,
    val effects: BattleMoveEffectsView? = null,
) {
    init {
        require(typeId.isNotBlank())
        require(power.isFinite() && power >= 0.0)
        require(accuracy.isFinite() && accuracy in 0.0..100.0)
        require(currentPp >= 0)
    }
}

enum class BattlePublicMoveKnowledge { EXACT_OWN, PUBLICLY_REVEALED }

data class BattlePublicMoveOptionView(
    val moveId: String,
    val details: BattleMoveCandidateView,
    val knowledge: BattlePublicMoveKnowledge,
) {
    init {
        require(moveId.isNotBlank())
    }
}

class BattlePokemonActionCatalogView(
    val battlePokemonId: UUID,
    moves: List<BattlePublicMoveOptionView>,
    val moveSetComplete: Boolean = false,
) {
    val moves: List<BattlePublicMoveOptionView> = Collections.unmodifiableList(ArrayList(moves))

    init {
        require(this.moves.map { it.moveId }.distinct().size == this.moves.size) {
            "Future action catalog cannot contain duplicate move IDs for one Pokemon"
        }
    }
}

class BattlePublicActionCatalogView(entries: List<BattlePokemonActionCatalogView>) {
    val entries: List<BattlePokemonActionCatalogView> = Collections.unmodifiableList(ArrayList(entries))
    private val byPokemon: Map<UUID, List<BattlePublicMoveOptionView>> = this.entries.associate {
        it.battlePokemonId to it.moves
    }

    init {
        require(byPokemon.size == this.entries.size) {
            "Future action catalog cannot contain duplicate Pokemon identities"
        }
    }

    fun forPokemon(battlePokemonId: UUID): List<BattlePublicMoveOptionView> = byPokemon[battlePokemonId].orEmpty()

    fun isMoveSetComplete(battlePokemonId: UUID): Boolean = entries.firstOrNull {
        it.battlePokemonId == battlePokemonId
    }?.moveSetComplete == true

    companion object {
        @JvmStatic fun empty() = BattlePublicActionCatalogView(emptyList())
    }
}

class BattleActionCandidate(
    val actionId: String,
    val kind: BattleActionKind,
    val actorSlot: Int? = null,
    val moveSlot: Int? = null,
    val moveId: String? = null,
    targets: List<BattleTargetSlot> = emptyList(),
    val switchPokemonId: UUID? = null,
    componentActionIds: List<String> = emptyList(),
    componentActions: List<BattleActionCandidate> = emptyList(),
    val mechanic: BattleMechanicCandidate? = null,
    val moveDetails: BattleMoveCandidateView? = null,
    val facts: BattleCandidateFactsView? = null,
    tags: Set<String> = emptySet(),
) {
    val targets: List<BattleTargetSlot> = targets.toList()
    val componentActionIds: List<String> = componentActionIds.toList()
    val componentActions: List<BattleActionCandidate> = componentActions.toList()
    val tags: Set<String> = tags.toSet()

    init {
        require(actionId.isNotBlank())
        require(actorSlot == null || actorSlot >= 0)
        require(moveSlot == null || moveSlot >= 0)
        require(targets.distinct().size == targets.size) { "Action targets must be unique" }
        require(componentActionIds.all { it.isNotBlank() })
        require(componentActionIds.distinct().size == componentActionIds.size) {
            "Composite action components must be unique"
        }
        require(tags.all { it.isNotBlank() })
        when (kind) {
            BattleActionKind.USE_MOVE -> {
                require(actorSlot != null && moveSlot != null)
                require(switchPokemonId == null && componentActionIds.isEmpty() && componentActions.isEmpty())
            }

            BattleActionKind.SWITCH -> {
                require(actorSlot != null && switchPokemonId != null)
                require(moveSlot == null && moveId == null && targets.isEmpty())
                require(componentActionIds.isEmpty() && componentActions.isEmpty() && mechanic == null && moveDetails == null)
            }

            BattleActionKind.COMPOSITE -> {
                require(componentActionIds.isNotEmpty())
                require(componentActions.isEmpty() || componentActions.map { it.actionId } == componentActionIds)
                require(componentActions.none { it.kind == BattleActionKind.COMPOSITE })
                require(actorSlot == null && moveSlot == null && moveId == null)
                require(targets.isEmpty() && switchPokemonId == null && mechanic == null && moveDetails == null)
            }

            BattleActionKind.FORFEIT, BattleActionKind.WAIT -> {
                require(actorSlot == null && moveSlot == null && moveId == null)
                require(targets.isEmpty() && switchPokemonId == null)
                require(componentActionIds.isEmpty() && componentActions.isEmpty() && mechanic == null && moveDetails == null)
            }
        }
    }
}

data class BattleBrainOpenContext(
    val battleId: UUID,
    val format: BattleFormat,
    val knowledgePolicy: BattleKnowledgePolicy = BattleKnowledgePolicy.FAIR_INFERENCE,
    val strategy: BattleStrategyBrief? = null,
    val trainerProfile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
    val learningScopeId: UUID? = null,
    val trainerPersonaId: String? = null,
) {
    init {
        require(trainerPersonaId == null || trainerPersonaId.isNotBlank())
    }
}

class BattleDecisionContext(
    val requestId: UUID,
    val state: BattleStateView,
    candidates: List<BattleActionCandidate>,
    val deadlineEpochMillis: Long,
    val memory: BattleTacticalMemoryView = BattleTacticalMemoryView.empty(),
    val publicActionCatalog: BattlePublicActionCatalogView = BattlePublicActionCatalogView.empty(),
) {
    val candidates: List<BattleActionCandidate> = candidates.toList()

    init {
        require(candidates.isNotEmpty())
        require(candidates.map { it.actionId }.distinct().size == candidates.size) {
            "Server-generated action ids must be unique within a decision request"
        }
    }
}

class BattleDecision(
    val requestId: UUID,
    val actionId: String,
    val confidence: Double? = null,
    tags: Set<String> = emptySet(),
    val advice: BattleDecisionAdvice? = null,
) {
    val tags: Set<String> = tags.toSet()

    init {
        require(actionId.isNotBlank())
        require(confidence == null || confidence in 0.0..1.0)
    }
}

enum class BattleBrainCloseOutcome { VICTORY, DEFEAT, NO_CONTEST, CANCELLED }

data class BattleBrainCloseResult(
    val outcome: BattleBrainCloseOutcome,
    val turns: Int,
) {
    init {
        require(turns >= 0)
    }
}

interface BattleBrainSession {
    val sessionId: UUID
}

interface BattleBrain {
    fun openSession(context: BattleBrainOpenContext): BattleBrainSession
    fun decide(session: BattleBrainSession, context: BattleDecisionContext): CompletionStage<BattleDecision>
    fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult)
}

fun interface BattleBrainFactory {
    fun create(): BattleBrain
}

object BattleBrainDefaults {
    const val DECISION_TIMEOUT_MILLIS: Long = 20_000L
}

enum class BattleDecisionValidationStatus { VALID, STALE_REQUEST, UNKNOWN_ACTION, DEADLINE_EXPIRED }

object BattleDecisionValidator {
    @JvmStatic
    fun validate(
        context: BattleDecisionContext,
        decision: BattleDecision,
        nowEpochMillis: Long,
    ): BattleDecisionValidationStatus = when {
        decision.requestId != context.requestId -> BattleDecisionValidationStatus.STALE_REQUEST
        nowEpochMillis > context.deadlineEpochMillis -> BattleDecisionValidationStatus.DEADLINE_EXPIRED
        context.candidates.none { it.actionId == decision.actionId } -> BattleDecisionValidationStatus.UNKNOWN_ACTION
        else -> BattleDecisionValidationStatus.VALID
    }
}
