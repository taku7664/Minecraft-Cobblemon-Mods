package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonFormStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView

/**
 * The form a mechanic puts the actor into, when the public state already describes that form.
 *
 * Mega Evolution was the one mechanic left unprojected. The reasoning was sound at the time - the
 * form data lives behind another mod, and a Mega priced from its un-transformed stats is a wrong
 * number rather than a cautious one - but the premise turned out to be false. Every battle form a
 * species has, Mega included, is already published on the Pokemon as `knownFormStates`: exact for
 * the trainer's own party, public species ranges for the opponent. Nothing needs to be invented and
 * nothing hidden is read; the mega spread was simply sitting one field away from the code that said
 * it could not be reached.
 *
 * Resolution stays deliberately timid. A species with one Mega form resolves to it. A species with
 * two - Charizard, Mewtwo - resolves only when the held stone names which one, because the stone is
 * public the moment the mechanic is offered. Anything else returns nothing and the candidate keeps
 * the behaviour it has today, which is to project no damage at all rather than a wrong one.
 */
internal object LocalMechanicFormResolution {
    fun transformedForm(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
    ): BattlePokemonFormStateView? {
        actor ?: return null
        val mechanic = candidate.mechanic ?: return null
        if (canonical(mechanic.mechanicId) != MEGA) return null
        val megaForms = actor.knownFormStates.values
            .filter { canonical(it.formId).startsWith(MEGA) }
            .sortedBy { canonical(it.formId) }
        megaForms.singleOrNull()?.let { return it }
        if (megaForms.isEmpty()) return null
        val variant = stoneVariant(actor.knownHeldItemId) ?: return null
        return megaForms.singleOrNull { canonical(it.formId).endsWith(variant) }
    }

    fun transformedStats(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
    ): BattleCombatStatRangesView? =
        candidate.mechanic?.transformedActorCombatStats ?: transformedForm(candidate, actor)?.combatStats

    fun transformedTypeIds(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
    ): Set<String> {
        val declared = candidate.mechanic?.transformedActorTypeIds.orEmpty()
        if (declared.isNotEmpty()) return declared
        return transformedForm(candidate, actor)?.knownTypeIds.orEmpty()
    }

    /**
     * Whether the resolved types replace the actor's originals rather than joining them.
     *
     * This is the whole difference between the two mechanics that change an attacker's typing.
     * Terastallization adds: a Fire type that Teras into Fire keeps its old bonus and gains a second,
     * which is why a move matching both is doubled. A Mega Evolution swaps the spread outright -
     * Mega Charizard X is Fire and Dragon where it was Fire and Flying - so its Fire moves are an
     * ordinary same-type attack and reading the addition rule into it would price every one of them
     * at twice what it is worth.
     */
    fun replacesOriginalTypes(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
    ): Boolean = candidate.mechanic?.transformedActorTypeIds.orEmpty().isEmpty() &&
        transformedForm(candidate, actor) != null

    /**
     * Which of a species' two Mega forms the held stone names.
     *
     * The stones are `charizardite_x` and `charizardite_y`, so the trailing letter is the whole
     * answer. A stone without one - the ordinary case, one Mega per species - never reaches here.
     */
    private fun stoneVariant(heldItemId: String?): String? {
        val item = canonical(heldItemId ?: return null)
        val letter = item.lastOrNull()?.takeIf { it in VARIANT_LETTERS } ?: return null
        if (!item.dropLast(1).endsWith(STONE_STEM)) return null
        return letter.toString()
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val MEGA = "mega"

    /** `charizardite_x` canonicalises to `charizarditex`, so the letter follows the stem. */
    private const val STONE_STEM = "ite"
    private val VARIANT_LETTERS = setOf('x', 'y')
}
