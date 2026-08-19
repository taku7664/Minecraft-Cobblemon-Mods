package jbro.cobblemon.morebattlecontent.api.ai

import java.util.UUID
import java.util.Collections

data class BattleTimedEffectView @JvmOverloads constructor(
    val effectId: String,
    val remainingTurns: Int?,
    val stacks: Int? = null,
    val remainingTurnsRange: BattleIntegerRange? = null,
) {
    init {
        require(effectId.isNotBlank())
        require(remainingTurns == null || remainingTurns > 0) {
            "Known effect duration must be positive"
        }
        require(stacks == null || stacks > 0) {
            "Known effect stack count must be positive"
        }
        require(remainingTurns == null || remainingTurnsRange == null) {
            "An effect duration must be exact or ranged, not both"
        }
        require(remainingTurnsRange == null || remainingTurnsRange.minimum < remainingTurnsRange.maximum) {
            "An equal duration range must be represented as exact remaining turns"
        }
    }
}

class BattleFieldStateView(
    val weather: BattleTimedEffectView?,
    val terrain: BattleTimedEffectView?,
    roomEffects: List<BattleTimedEffectView>,
    globalEffects: List<BattleTimedEffectView>,
    sideConditions: Map<BattleSide, List<BattleTimedEffectView>>,
) {
    val roomEffects: List<BattleTimedEffectView> = roomEffects.toList()
    val globalEffects: List<BattleTimedEffectView> = globalEffects.toList()
    val sideConditions: Map<BattleSide, List<BattleTimedEffectView>> =
        sideConditions.mapValues { (_, effects) -> effects.toList() }.toMap()

    init {
        require(sideConditions.keys == BattleSide.entries.toSet()) {
            "Field state must distinguish both battle sides"
        }
        requireUniqueEffects(roomEffects, "room")
        requireUniqueEffects(globalEffects, "global")
        sideConditions.forEach { (side, effects) -> requireUniqueEffects(effects, side.name.lowercase()) }
    }

    companion object {
        @JvmStatic
        fun empty(): BattleFieldStateView = BattleFieldStateView(
            weather = null,
            terrain = null,
            roomEffects = emptyList(),
            globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() },
        )
    }
}

enum class BattleObservedEventKind {
    ACTION_ORDER,
    MOVE_USED,
    SWITCHED,
    FAINTED,
    HP_CHANGED,
    STATUS_CHANGED,
    ABILITY_REVEALED,
    HELD_ITEM_REVEALED,
    FIELD_EFFECT_CHANGED,
    MOVE_OUTCOME,
}

enum class BattleMoveOutcomeKind {
    MISSED,
    FAILED,
    BLOCKED,
    NO_TARGET,
    CANNOT_ACT,
    CRITICAL_HIT,
    SUPER_EFFECTIVE,
    RESISTED,
    IMMUNE,
    HIT_COUNT,
    SUBSTITUTE_DAMAGED,
    PROTECTION_STARTED,
}

data class BattleMoveOutcomeView @JvmOverloads constructor(
    val kind: BattleMoveOutcomeKind,
    val moveId: String? = null,
    val publicEffectId: String? = null,
    val hitCount: Int? = null,
) {
    init {
        require(moveId == null || moveId.isNotBlank())
        require(publicEffectId == null || publicEffectId.isNotBlank())
        require((kind == BattleMoveOutcomeKind.HIT_COUNT) == (hitCount != null)) {
            "Only a hit-count outcome may carry a count, and it must carry one"
        }
        require(hitCount == null || hitCount > 0)
        require(moveId == null || kind in MOVE_DETAIL_OUTCOMES) {
            "$kind cannot identify a causing move"
        }
        require(publicEffectId == null || kind in EFFECT_DETAIL_OUTCOMES) {
            "$kind cannot identify a public effect"
        }
        require(kind != BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED || publicEffectId == "substitute") {
            "A substitute-damaged outcome must identify the public substitute effect"
        }
        require(kind != BattleMoveOutcomeKind.PROTECTION_STARTED || publicEffectId == "protect") {
            "A protection-started outcome must identify the public protect effect"
        }
    }

    private companion object {
        val MOVE_DETAIL_OUTCOMES = setOf(
            BattleMoveOutcomeKind.MISSED,
            BattleMoveOutcomeKind.FAILED,
            BattleMoveOutcomeKind.BLOCKED,
            BattleMoveOutcomeKind.NO_TARGET,
            BattleMoveOutcomeKind.CANNOT_ACT,
        )
        val EFFECT_DETAIL_OUTCOMES = setOf(
            BattleMoveOutcomeKind.BLOCKED,
            BattleMoveOutcomeKind.CANNOT_ACT,
            BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
            BattleMoveOutcomeKind.PROTECTION_STARTED,
        )
    }
}

class BattleObservedEventView @JvmOverloads constructor(
    val sequence: Long,
    val turn: Int,
    val kind: BattleObservedEventKind,
    val actorPokemonId: UUID? = null,
    targetPokemonIds: List<UUID> = emptyList(),
    val publicValueId: String? = null,
    val hpFractionDelta: Double? = null,
    val baseMovePriority: Int? = null,
    val precedingActionSequence: Long? = null,
    val precedingActionActorPokemonId: UUID? = null,
    val precedingActionMoveId: String? = null,
    val publicSourceEffectId: String? = null,
    val moveOutcome: BattleMoveOutcomeView? = null,
) {
    val targetPokemonIds: List<UUID> = targetPokemonIds.toList()

    init {
        require(sequence >= 0)
        require(turn >= 0)
        require(targetPokemonIds.distinct().size == targetPokemonIds.size)
        require(publicValueId == null || publicValueId.isNotBlank())
        require(hpFractionDelta == null || hpFractionDelta.isFinite() && hpFractionDelta in -1.0..1.0)
        require(publicSourceEffectId == null || publicSourceEffectId.isNotBlank())
        if (kind in ID_REVEAL_EVENTS) require(!publicValueId.isNullOrBlank()) {
            "$kind must identify the publicly revealed value"
        }
        require(baseMovePriority == null || kind == BattleObservedEventKind.ACTION_ORDER) {
            "Base move priority belongs only to an action-order observation"
        }
        if (baseMovePriority != null) {
            require(actorPokemonId != null && !publicValueId.isNullOrBlank()) {
                "An action-order priority requires its public actor and move"
            }
        }
        val precedingActionFields = listOf(
            precedingActionSequence,
            precedingActionActorPokemonId,
            precedingActionMoveId,
        )
        require(precedingActionFields.all { it == null } || precedingActionFields.all { it != null }) {
            "A preceding action link must be complete or absent"
        }
        if (precedingActionSequence != null) {
            require(kind == BattleObservedEventKind.HP_CHANGED && hpFractionDelta != null && hpFractionDelta < 0.0) {
                "A preceding action link belongs only to observed HP loss"
            }
            require(precedingActionSequence >= 0 && precedingActionSequence < sequence) {
                "A preceding action must identify an earlier event"
            }
            require(!precedingActionMoveId.isNullOrBlank())
        }
        require(publicSourceEffectId == null || kind == BattleObservedEventKind.HP_CHANGED) {
            "A public source effect belongs only to an HP-change observation"
        }
        require(publicSourceEffectId == null || precedingActionSequence == null) {
            "A sourced HP change cannot also claim a preceding action link"
        }
        require((kind == BattleObservedEventKind.MOVE_OUTCOME) == (moveOutcome != null)) {
            "A move-outcome event must carry exactly one structured outcome"
        }
    }

    private companion object {
        val ID_REVEAL_EVENTS = setOf(
            BattleObservedEventKind.MOVE_USED,
            BattleObservedEventKind.ABILITY_REVEALED,
            BattleObservedEventKind.HELD_ITEM_REVEALED,
        )
    }
}

enum class BattleInferenceConfidence { CONFIRMED, NEAR_CERTAIN, LIKELY, POSSIBLE, RULED_OUT, UNKNOWN }

enum class BattleAbilityAvailability { REGULAR, HIDDEN }

enum class BattleInferenceBasis {
    PUBLIC_SPECIES_RULES,
    PUBLIC_REVEAL,
    ACTION_ORDER,
    DAMAGE_OBSERVATION,
    FIELD_STATE,
    STATUS_STATE,
}

class BattleInferenceView @JvmOverloads constructor(
    val subjectPokemonId: UUID,
    val categoryId: String,
    val candidateId: String?,
    val confidence: BattleInferenceConfidence,
    val probabilityRange: BattleFractionRange? = null,
    basis: Set<BattleInferenceBasis> = emptySet(),
    evidenceEventSequences: List<Long> = emptyList(),
    val relatedPokemonId: UUID? = null,
    val abilityAvailability: BattleAbilityAvailability? = null,
) {
    val basis: Set<BattleInferenceBasis> = Collections.unmodifiableSet(LinkedHashSet(basis))
    val evidenceEventSequences: List<Long> = Collections.unmodifiableList(ArrayList(evidenceEventSequences))

    init {
        require(categoryId.isNotBlank())
        require(candidateId == null || candidateId.isNotBlank())
        require(abilityAvailability == null || categoryId == "ability") {
            "Ability availability belongs only to an ability inference"
        }
        require((confidence == BattleInferenceConfidence.UNKNOWN) == (candidateId == null)) {
            "Unknown inference must not claim a candidate, and a candidate requires a confidence"
        }
        require(this.evidenceEventSequences.all { it >= 0 }) { "Inference evidence sequence cannot be negative" }
        require(this.evidenceEventSequences.distinct().size == this.evidenceEventSequences.size) {
            "Inference evidence sequences must be unique"
        }
        require(confidence != BattleInferenceConfidence.UNKNOWN || probabilityRange == null) {
            "Unknown inference cannot claim a probability range"
        }
        require(confidence != BattleInferenceConfidence.CONFIRMED || probabilityRange == null ||
            probabilityRange == BattleFractionRange(1.0, 1.0)) {
            "Confirmed inference probability must be exactly one when supplied"
        }
        require(confidence != BattleInferenceConfidence.RULED_OUT || probabilityRange == null ||
            probabilityRange == BattleFractionRange(0.0, 0.0)) {
            "Ruled-out inference probability must be exactly zero when supplied"
        }
    }

    @JvmOverloads
    fun copy(
        subjectPokemonId: UUID = this.subjectPokemonId,
        categoryId: String = this.categoryId,
        candidateId: String? = this.candidateId,
        confidence: BattleInferenceConfidence = this.confidence,
        probabilityRange: BattleFractionRange? = this.probabilityRange,
        basis: Set<BattleInferenceBasis> = this.basis,
        evidenceEventSequences: List<Long> = this.evidenceEventSequences,
        relatedPokemonId: UUID? = this.relatedPokemonId,
        abilityAvailability: BattleAbilityAvailability? = this.abilityAvailability,
    ) = BattleInferenceView(
        subjectPokemonId,
        categoryId,
        candidateId,
        confidence,
        probabilityRange,
        basis,
        evidenceEventSequences,
        relatedPokemonId,
        abilityAvailability,
    )

    operator fun component1() = subjectPokemonId
    operator fun component2() = categoryId
    operator fun component3() = candidateId
    operator fun component4() = confidence

    override fun equals(other: Any?): Boolean = other is BattleInferenceView &&
        subjectPokemonId == other.subjectPokemonId && categoryId == other.categoryId &&
        candidateId == other.candidateId && confidence == other.confidence &&
        probabilityRange == other.probabilityRange && basis == other.basis &&
        evidenceEventSequences == other.evidenceEventSequences && relatedPokemonId == other.relatedPokemonId &&
        abilityAvailability == other.abilityAvailability

    override fun hashCode(): Int {
        var result = subjectPokemonId.hashCode()
        result = 31 * result + categoryId.hashCode()
        result = 31 * result + (candidateId?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (probabilityRange?.hashCode() ?: 0)
        result = 31 * result + basis.hashCode()
        result = 31 * result + evidenceEventSequences.hashCode()
        result = 31 * result + (relatedPokemonId?.hashCode() ?: 0)
        return 31 * result + (abilityAvailability?.hashCode() ?: 0)
    }

    override fun toString(): String = "BattleInferenceView(" +
        "subjectPokemonId=$subjectPokemonId, categoryId=$categoryId, candidateId=$candidateId, " +
        "confidence=$confidence, probabilityRange=$probabilityRange, basis=$basis, " +
        "evidenceEventSequences=$evidenceEventSequences, relatedPokemonId=$relatedPokemonId, " +
        "abilityAvailability=$abilityAvailability)"
}

private fun requireUniqueEffects(effects: List<BattleTimedEffectView>, area: String) {
    require(effects.map { it.effectId }.distinct().size == effects.size) {
        "Duplicate $area field effect"
    }
}
