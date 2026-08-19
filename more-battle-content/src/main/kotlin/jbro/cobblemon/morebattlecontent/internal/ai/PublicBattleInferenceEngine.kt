package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

internal fun interface PublicSpeciesInferenceKnowledge {
    /** Returns every ability allowed by public species/form rules, or null when the rule data is unavailable. */
    fun possibleAbilities(speciesId: String, formId: String?): List<PublicAbilityPossibility>?
}

internal data class PublicAbilityPossibility(
    val abilityId: String,
    val availability: BattleAbilityAvailability,
) {
    init {
        require(abilityId.isNotBlank())
    }
}

/** Produces hypotheses from public rule data only; it has no access to live Pokemon internals. */
internal object PublicBattleInferenceEngine {
    fun infer(
        pokemon: List<BattlePokemonStateView>,
        speciesKnowledge: PublicSpeciesInferenceKnowledge,
    ): List<BattleInferenceView> = infer(pokemon, speciesKnowledge, emptyList())

    fun infer(
        pokemon: List<BattlePokemonStateView>,
        speciesKnowledge: PublicSpeciesInferenceKnowledge,
        observedEvents: List<BattleObservedEventView>,
    ): List<BattleInferenceView> = abilityPossibilities(pokemon, speciesKnowledge) +
        observedActionOrderRelations(pokemon, observedEvents)

    private fun abilityPossibilities(
        pokemon: List<BattlePokemonStateView>,
        speciesKnowledge: PublicSpeciesInferenceKnowledge,
    ): List<BattleInferenceView> = pokemon.asSequence()
        .filter { it.side == BattleSide.OPPONENT && it.knownAbilityId == null && !it.fainted }
        .flatMap { subject ->
            speciesKnowledge.possibleAbilities(subject.speciesId, subject.formId)
                .orEmpty()
                .asSequence()
                .distinct()
                .sortedWith(compareBy<PublicAbilityPossibility> { it.abilityId }.thenBy { it.availability })
                .map { possibility ->
                    BattleInferenceView(
                        subjectPokemonId = subject.battlePokemonId,
                        categoryId = "ability",
                        candidateId = possibility.abilityId,
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = possibility.availability,
                    )
                }
        }
        .toList()

    private fun observedActionOrderRelations(
        pokemon: List<BattlePokemonStateView>,
        observedEvents: List<BattleObservedEventView>,
    ): List<BattleInferenceView> {
        val sides = pokemon.associate { it.battlePokemonId to it.side }
        val evidenceByRelation = linkedMapOf<ActionOrderRelation, LinkedHashSet<Long>>()
        observedEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.ACTION_ORDER }
            .filter { it.actorPokemonId != null && it.baseMovePriority != null }
            .sortedBy { it.sequence }
            .groupBy { it.turn }
            .toSortedMap()
            .values
            .forEach { turnEvents ->
                if (turnEvents.groupingBy { it.actorPokemonId }.eachCount().any { it.value > 1 }) return@forEach
                val opponentActions = turnEvents.filter { sides[it.actorPokemonId] == BattleSide.OPPONENT }
                val allyActions = turnEvents.filter { sides[it.actorPokemonId] == BattleSide.ALLY }
                opponentActions.forEach { opponentAction ->
                    allyActions.filter { it.baseMovePriority == opponentAction.baseMovePriority }.forEach { allyAction ->
                        val relation = ActionOrderRelation(
                            subjectPokemonId = requireNotNull(opponentAction.actorPokemonId),
                            relatedPokemonId = requireNotNull(allyAction.actorPokemonId),
                            candidateId = if (opponentAction.sequence < allyAction.sequence) {
                                BEFORE_AT_SAME_BASE_PRIORITY
                            } else {
                                AFTER_AT_SAME_BASE_PRIORITY
                            },
                        )
                        evidenceByRelation.getOrPut(relation, ::linkedSetOf).apply {
                            add(opponentAction.sequence)
                            add(allyAction.sequence)
                        }
                    }
                }
            }
        return evidenceByRelation.entries
            .sortedWith(
                compareBy<Map.Entry<ActionOrderRelation, LinkedHashSet<Long>>> {
                    it.key.subjectPokemonId.toString()
                }.thenBy { it.key.relatedPokemonId.toString() }.thenBy { it.key.candidateId },
            )
            .map { (relation, evidence) ->
                BattleInferenceView(
                    subjectPokemonId = relation.subjectPokemonId,
                    categoryId = OBSERVED_ACTION_ORDER,
                    candidateId = relation.candidateId,
                    confidence = BattleInferenceConfidence.CONFIRMED,
                    basis = setOf(BattleInferenceBasis.ACTION_ORDER),
                    evidenceEventSequences = evidence.sorted(),
                    relatedPokemonId = relation.relatedPokemonId,
                )
            }
    }

    private data class ActionOrderRelation(
        val subjectPokemonId: java.util.UUID,
        val relatedPokemonId: java.util.UUID,
        val candidateId: String,
    )

    private const val OBSERVED_ACTION_ORDER = "observed_action_order"
    private const val BEFORE_AT_SAME_BASE_PRIORITY = "BEFORE_AT_SAME_BASE_PRIORITY"
    private const val AFTER_AT_SAME_BASE_PRIORITY = "AFTER_AT_SAME_BASE_PRIORITY"
}
