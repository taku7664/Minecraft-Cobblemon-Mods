package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.policy.LocalDoubleLeadPairEvaluation
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalDoubleLeadOpeningMechanicsTest {
    @Test
    fun `known Commander changes the Dondozo opening through its actual stat boosts`() {
        assertTrue(commanderScore("commander", tatsugiriAttack = 10) > commanderScore("none", tatsugiriAttack = 10),
            "The known five +2 boosts must affect the pair, rather than leaving its base matchups unchanged")
    }

    @Test
    fun `the commanding Tatsugiri cannot contribute an attack after its action is cancelled`() {
        assertEquals(commanderScore("commander", tatsugiriAttack = 10),
            commanderScore("commander", tatsugiriAttack = 300), 1e-9,
            "Changing the cancelled actor's offensive stats cannot change its pair's attacking pressure")
    }

    @Test
    fun `Commander requires a Dondozo partner instead of awarding an ability name bonus`() {
        assertEquals(commanderScore("none", partnerSpecies = "cobblemon:gyarados"),
            commanderScore("commander", partnerSpecies = "cobblemon:gyarados"), 1e-9)
    }

    @Test
    fun `known Neutralizing Gas suppresses Commander before the pair can combine`() {
        assertEquals(commanderScore("none", partnerAbility = "neutralizinggas"),
            commanderScore("commander", partnerAbility = "neutralizinggas"), 1e-9)
    }

    @Test
    fun `Surf prices its partner damage and respects that partner's known Water Absorb`() {
        assertTrue(spreadScore(BattleMoveTargetPattern.ALL_ADJACENT, "waterabsorb") >
            spreadScore(BattleMoveTargetPattern.ALL_ADJACENT, "none"),
            "Surf beside a vulnerable partner loses own HP; the same known immune partner prevents that loss")
    }

    @Test
    fun `a spread attack still receives its reduction when the partner is immune`() {
        assertTrue(spreadScore(BattleMoveTargetPattern.ALL_ADJACENT, "waterabsorb") <
            spreadScore(BattleMoveTargetPattern.SELECTED_OPPONENT, "waterabsorb"),
            "Two living foes retain the 0.75 damage reduction even when the partner takes no damage")
    }

    @Test
    fun `an opponents only spread attack cannot charge damage to its own partner`() {
        assertEquals(spreadScore(BattleMoveTargetPattern.ALL_OPPONENTS, "none"),
            spreadScore(BattleMoveTargetPattern.ALL_OPPONENTS, "waterabsorb"), 1e-9)
    }

    @Test
    fun `own Sand Stream lowers Special attacking pressure against public Rock foes`() {
        fun score(ability: String) = score(context(listOf(own(1, "water"), own(2, "water")),
            listOf(move("surf", "water", BattleMoveDamageCategory.SPECIAL),
                move("waterpulse", "water", BattleMoveDamageCategory.SPECIAL)),
            listOf(ability, "none"), foeType = "rock"))
        assertTrue(score("sandstream") < score("none"), "The known field also protects the opposing Rock type")
    }

    @Test
    fun `own Snow Warning lowers Physical attacking pressure against public Ice foes`() {
        fun score(ability: String) = score(context(listOf(own(1, "water"), own(2, "water")),
            listOf(move("waterfall", "water", BattleMoveDamageCategory.PHYSICAL),
                move("aquatail", "water", BattleMoveDamageCategory.PHYSICAL)),
            listOf(ability, "none"), foeType = "ice"))
        assertTrue(score("snowwarning") < score("none"), "The known field also protects the opposing Ice type")
    }

    private fun commanderScore(ability: String, tatsugiriAttack: Int = 10,
        partnerSpecies: String = "cobblemon:dondozo", partnerAbility: String = "none"): Double = score(context(
        listOf(own(1, "water", partnerSpecies, attack = 100, speed = 50),
            own(2, "water", "cobblemon:tatsugiri", attack = tatsugiriAttack, speed = 100)),
        listOf(move("waterfall", "water", BattleMoveDamageCategory.PHYSICAL),
            move("surf", "water", BattleMoveDamageCategory.SPECIAL)),
        listOf(partnerAbility, ability), foeType = "normal"))

    private fun spreadScore(pattern: BattleMoveTargetPattern, partnerAbility: String): Double = score(context(
        listOf(own(1, "water"), own(2, "water")),
        listOf(move("surf", "water", BattleMoveDamageCategory.SPECIAL, pattern),
            move("waterpulse", "water", BattleMoveDamageCategory.SPECIAL)),
        listOf("none", partnerAbility), foeType = "fire"))

    private fun score(context: BattleLeadChoiceContext) = LocalDoubleLeadPairEvaluation.scores(context,
        context.ownTeam, context.opponentTeamPreview.pokemon, detailed = true)
        .getValue(listOf(UUID(0, 1), UUID(0, 2)))

    private fun context(own: List<BattlePokemonStateView>, moves: List<BattlePublicMoveOptionView>,
        abilities: List<String>, foeType: String): BattleLeadChoiceContext {
        val catalog = BattlePublicActionCatalogView(own.mapIndexed { index, pokemon ->
            BattlePokemonActionCatalogView(pokemon.battlePokemonId, listOf(moves[index]), moveSetComplete = true)
        })
        val builds = BattleExactOwnTeamView(own.mapIndexed { index, pokemon ->
            BattleExactPokemonBuildView(pokemon.battlePokemonId, abilities[index], null, "serious", "N",
                EVS, EVS.mapValues { 31 }, null, pokemon.speciesId)
        })
        val preview = BattleOpponentTeamPreviewView(2, (0..1).map { index ->
            BattleOpponentTeamPreviewPokemonView(index, "cobblemon:$foeType", null, 50, setOf(foeType),
                BattleCombatStatRangesView(BattleIntegerRange(149, 151), BattleIntegerRange(99, 101),
                    BattleIntegerRange(99, 101), BattleIntegerRange(99, 101), BattleIntegerRange(99, 101),
                    BattleIntegerRange(149, 151), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        })
        return BattleLeadChoiceContext(BattleFormat.DOUBLE, own, catalog, builds, preview, BattleTrainerProfile.boss())
    }

    private fun move(id: String, type: String, category: BattleMoveDamageCategory,
        pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT) = BattlePublicMoveOptionView(id,
        BattleMoveCandidateView(type, category, 90.0, 100.0, 0, 10, targetPattern = pattern),
        BattlePublicMoveKnowledge.EXACT_OWN)

    private fun own(id: Long, type: String, species: String = "cobblemon:$type", attack: Int = 100, speed: Int = 100) =
        BattlePokemonStateView(UUID(0, id), BattleSide.ALLY, null, species, null, 50, 1.0, null, emptyMap(),
            emptySet(), null, null, false, setOf(type), BattleCombatStatRangesView.exact(150, attack, 100, attack, 100, speed))

    private companion object {
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
