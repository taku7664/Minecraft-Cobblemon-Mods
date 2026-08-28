package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A Mega Evolution is projected from the form the public state already publishes.
 *
 * It was the last mechanic left unprojected, on the reasoning that its spread lives behind another
 * mod. The reasoning was wrong about where the data is: every battle form a species has is already
 * on the Pokemon as `knownFormStates`, so the Mega spread was one field away from the code that
 * declined to reach it. Nothing here reads a hidden set - an opponent's forms arrive as the same
 * public species ranges everything else uses.
 *
 * The contract it must not break is the one that held before: a mechanic that cannot be resolved
 * still projects nothing, because a wrong number is worse than an absent one.
 */
class LocalMegaFormProjectionTest {
    @Test
    fun `a mega evolution is projected from its own form`() {
        val ordinary = requireNotNull(damage(mechanicId = null)).maximum
        val mega = requireNotNull(damage(mechanicId = "mega")).maximum
        assertTrue(
            mega > ordinary * 1.4,
            "The Mega form's attack is half again the base one, so its damage must rise with it; " +
                "was $mega against $ordinary.",
        )
    }

    @Test
    fun `a mega swaps the typing instead of adding to it`() {
        val facts = requireNotNull(facts(mechanicId = "mega", moveTypeId = "dragon"))
        assertEquals(
            1.5, requireNotNull(facts.baseSameTypeAttackBonus), 1.0e-9,
            "Dragon is the Mega form's own type, so it is an ordinary same-type attack.",
        )
        val old = requireNotNull(facts(mechanicId = "mega", moveTypeId = "flying"))
        assertEquals(
            1.0, requireNotNull(old.baseSameTypeAttackBonus), 1.0e-9,
            "Flying is the type the Mega gave up, so the bonus goes with it - " +
                "the Tera rule that keeps both must not reach a form swap.",
        )
    }

    @Test
    fun `two mega forms without a stone stay unprojected`() {
        val ambiguous = damage(
            mechanicId = "mega",
            forms = listOf(megaForm("mega_x", 200, setOf("fire", "dragon")), megaForm("mega_y", 200, setOf("fire", "flying"))),
            heldItemId = null,
        )
        assertNull(ambiguous, "With two Megas and no stone there is nothing to resolve, so nothing is claimed.")
    }

    @Test
    fun `the held stone names which of two mega forms is coming`() {
        val resolved = damage(
            mechanicId = "mega",
            forms = listOf(megaForm("mega_x", 200, setOf("fire", "dragon")), megaForm("mega_y", 100, setOf("fire", "flying"))),
            heldItemId = "mega_showdown:charizardite_x",
        )
        assertNotNull(resolved, "The stone names the X form, which is enough to resolve it.")
        val other = requireNotNull(
            damage(
                mechanicId = "mega",
                forms = listOf(megaForm("mega_x", 200, setOf("fire", "dragon")), megaForm("mega_y", 100, setOf("fire", "flying"))),
                heldItemId = "mega_showdown:charizardite_y",
            ),
        )
        assertTrue(
            requireNotNull(resolved).maximum > other.maximum,
            "And the two forms are told apart, not averaged.",
        )
    }

    @Test
    fun `a mechanic with no resolvable form still projects nothing`() {
        assertNull(
            damage(mechanicId = "mega", forms = emptyList()),
            "A species whose Mega form is absent from the public state keeps the old behaviour.",
        )
    }

    private fun megaForm(formId: String, attack: Int, types: Set<String>) = BattlePokemonFormStateView(
        formId = formId,
        knownTypeIds = types,
        combatStats = BattleCombatStatRangesView.exact(150, attack, 100, 100, 100, 100),
    )

    private fun damage(
        mechanicId: String?,
        forms: List<BattlePokemonFormStateView> = listOf(megaForm("mega", 200, setOf("fire", "dragon"))),
        heldItemId: String? = "mega_showdown:charizardite",
        moveTypeId: String = "normal",
    ): BattleDamageFractionRange? =
        facts(mechanicId, forms, heldItemId, moveTypeId)?.standardDamageFractionRange

    private fun facts(
        mechanicId: String?,
        forms: List<BattlePokemonFormStateView> = listOf(megaForm("mega", 200, setOf("fire", "dragon"))),
        heldItemId: String? = "mega_showdown:charizardite",
        moveTypeId: String = "normal",
    ): BattleCandidateFactsView? {
        val ally = BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = BattleSide.ALLY, activeSlot = 0,
            speciesId = "cobblemon:charizard", formId = null, level = 50, hpFraction = 1.0,
            statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = heldItemId, fainted = false,
            knownTypeIds = setOf("fire", "flying"),
            combatStats = BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100),
            knownFormStates = forms.associateBy { it.formId },
        )
        val opponent = BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = BattleSide.OPPONENT, activeSlot = 0,
            speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
            statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = null, fainted = false,
            knownTypeIds = setOf("normal"),
            combatStats = publicExactStats(300, 100, 100, 100, 100, 100),
        )
        val move = BattleActionCandidate(
            actionId = "probe", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:probe", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            mechanic = mechanicId?.let { BattleMechanicCandidate(mechanicId = it, target = null, publicCost = null) },
            moveDetails = BattleMoveCandidateView(
                typeId = moveTypeId, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0,
                accuracy = 100.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicBattleTacticalCalculator.calculate(context).candidates.single().facts
    }
}
