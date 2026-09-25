package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

internal enum class NativeBattleWorldPosteriorIssueCode {
    NO_PRIOR_WORLDS,
    DUPLICATE_WORLD_ID,
    PRIOR_NOT_NORMALIZED,
    WORLD_ROSTER_MISMATCH,
    INVALID_CONFIRMED_TERA_ID,
    CONFLICTING_CONFIRMED_TERA,
    NO_WORLD_MATCHES_CONFIRMED_TERA,
}

internal data class NativeBattleWorldPosteriorIssue(
    val code: NativeBattleWorldPosteriorIssueCode,
    val battlePokemonId: UUID? = null,
)

internal data class NativeBattleWorldPosteriorUpdate(
    val worlds: List<NativeBattleWorldHypothesis>,
    val issues: List<NativeBattleWorldPosteriorIssue>,
) {
    init {
        require((worlds.isEmpty()) == issues.isNotEmpty()) {
            "A native world posterior is either non-empty or unavailable with explicit issues"
        }
    }
}

/**
 * Conditions complete native worlds on persistent, exact public evidence.
 *
 * Recent observed events are intentionally not consulted here: the public observer owns their
 * bounded retention, while confirmed inferences are the durable battle-long ledger. A reveal that
 * contradicts every prior world fails closed instead of reviving an unrelated build.
 */
internal object NativeBattleWorldPosteriorUpdater {
    fun update(
        worlds: List<NativeBattleWorldHypothesis>,
        state: BattleStateView,
    ): NativeBattleWorldPosteriorUpdate {
        val issues = linkedSetOf<NativeBattleWorldPosteriorIssue>()
        if (worlds.isEmpty()) {
            issues += NativeBattleWorldPosteriorIssue(NativeBattleWorldPosteriorIssueCode.NO_PRIOR_WORLDS)
        }
        if (worlds.map(NativeBattleWorldHypothesis::hypothesisId).distinct().size != worlds.size) {
            issues += NativeBattleWorldPosteriorIssue(NativeBattleWorldPosteriorIssueCode.DUPLICATE_WORLD_ID)
        }

        val statePokemonIds = state.pokemon.mapTo(linkedSetOf()) { it.battlePokemonId }
        worlds.forEach { world ->
            if (world.pokemon.mapTo(linkedSetOf()) { it.battlePokemonId } != statePokemonIds) {
                issues += NativeBattleWorldPosteriorIssue(NativeBattleWorldPosteriorIssueCode.WORLD_ROSTER_MISMATCH)
            }
        }
        if (worlds.isNotEmpty() && abs(worlds.sumOf { it.probability } - 1.0) > NORMALIZATION_EPSILON) {
            issues += NativeBattleWorldPosteriorIssue(NativeBattleWorldPosteriorIssueCode.PRIOR_NOT_NORMALIZED)
        }
        if (issues.isNotEmpty()) return failure(issues)

        val confirmedTeraByPokemon = linkedMapOf<UUID, String>()
        state.inferences.asSequence()
            .filter { it.categoryId == TERA_TYPE_CATEGORY }
            .filter { it.confidence == BattleInferenceConfidence.CONFIRMED }
            .filter { BattleInferenceBasis.PUBLIC_REVEAL in it.basis }
            .forEach { inference ->
                val candidate = normalizedTeraId(requireNotNull(inference.candidateId))
                if (candidate.isBlank()) {
                    issues += NativeBattleWorldPosteriorIssue(
                        NativeBattleWorldPosteriorIssueCode.INVALID_CONFIRMED_TERA_ID,
                        inference.subjectPokemonId,
                    )
                    return@forEach
                }
                val previous = confirmedTeraByPokemon.putIfAbsent(inference.subjectPokemonId, candidate)
                if (previous != null && previous != candidate) {
                    issues += NativeBattleWorldPosteriorIssue(
                        NativeBattleWorldPosteriorIssueCode.CONFLICTING_CONFIRMED_TERA,
                        inference.subjectPokemonId,
                    )
                }
            }
        if (issues.isNotEmpty()) return failure(issues)
        if (confirmedTeraByPokemon.isEmpty()) return NativeBattleWorldPosteriorUpdate(worlds.toList(), emptyList())

        var matching = worlds
        confirmedTeraByPokemon.forEach { (pokemonId, confirmedTera) ->
            matching = matching.filter { world ->
                val build = world.pokemon.single { it.battlePokemonId == pokemonId }
                build.teraTypeId?.let(::normalizedTeraId) == confirmedTera
            }
            if (matching.isEmpty()) {
                return failure(listOf(NativeBattleWorldPosteriorIssue(
                    NativeBattleWorldPosteriorIssueCode.NO_WORLD_MATCHES_CONFIRMED_TERA,
                    pokemonId,
                )))
            }
        }

        if (matching.size == worlds.size) return NativeBattleWorldPosteriorUpdate(worlds.toList(), emptyList())
        val retainedProbability = matching.sumOf { it.probability }
        return NativeBattleWorldPosteriorUpdate(
            worlds = matching.map { it.copy(probability = it.probability / retainedProbability) },
            issues = emptyList(),
        )
    }

    private fun failure(issues: Collection<NativeBattleWorldPosteriorIssue>) =
        NativeBattleWorldPosteriorUpdate(emptyList(), issues.toList())

    private fun normalizedTeraId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private const val TERA_TYPE_CATEGORY = "tera_type"
    private const val NORMALIZATION_EPSILON = 1e-9
}
