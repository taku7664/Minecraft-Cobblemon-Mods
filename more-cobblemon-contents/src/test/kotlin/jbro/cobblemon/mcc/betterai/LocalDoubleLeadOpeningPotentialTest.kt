package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.policy.LocalDoubleLeadPairEvaluation
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalDoubleLeadOpeningPotentialTest {
    @Test
    fun `known Fake Out and relevant setup have joint opening potential beyond separate move availability`() {
        val supported = setupScore(fakeOut = true, setup = true) - setupScore(fakeOut = true, setup = false)
        val exposed = setupScore(fakeOut = false, setup = true) - setupScore(fakeOut = false, setup = false)
        assertTrue(supported > exposed + 1e-9,
            "The declared flinch can remove one public type-pressure exposure while the partner spends its attack on a relevant boost")
    }

    @Test
    fun `Fake Out cannot create a setup window through a publicly known Ghost immunity`() {
        assertEquals(setupScore(fakeOut = false, setup = true, foeType = "ghost"),
            setupScore(fakeOut = true, setup = true, foeType = "ghost"), 1e-9)
    }

    @Test
    fun `own Psychic Terrain prevents an opening Fake Out setup window against grounded foes`() {
        assertEquals(setupScore(fakeOut = false, setup = true, supportAbility = "psychicsurge"),
            setupScore(fakeOut = true, setup = true, supportAbility = "psychicsurge"), 1e-9)
    }

    @Test
    fun `an Attack boost cannot benefit a partner whose entire exact attack set is Special`() {
        assertEquals(setupScore(fakeOut = true, setup = false),
            setupScore(fakeOut = true, setup = true, stage = "attack"), 1e-9)
    }

    @Test
    fun `a setup move name without a declared rank effect cannot invent a boost`() {
        assertEquals(setupScore(fakeOut = true, setup = false),
            setupScore(fakeOut = true, setup = true, declared = false), 1e-9)
    }

    @Test
    fun `known Trick Room has opening potential when both own leads are slower than the public foe ranges`() {
        assertTrue(roomScore(enabled = true, speeds = listOf(50, 50)) > roomScore(enabled = false, speeds = listOf(50, 50)),
            "The real declared room reverses both public speed matchups after costing its user's first attack")
    }

    @Test
    fun `a team that is already faster retains its ordinary opening instead of assuming Trick Room is free`() {
        assertEquals(roomScore(enabled = false, speeds = listOf(250, 250)),
            roomScore(enabled = true, speeds = listOf(250, 250)), 1e-9)
    }

    @Test
    fun `Trick Room must price its fast partner's lost speed edge as well as its slow setter's gain`() {
        assertEquals(roomScore(enabled = false, speeds = listOf(50, 250)),
            roomScore(enabled = true, speeds = listOf(50, 250)), 1e-9)
    }

    @Test
    fun `a Trick Room move name without a declared field effect cannot invent a room`() {
        assertEquals(roomScore(enabled = false, speeds = listOf(50, 50)),
            roomScore(enabled = true, speeds = listOf(50, 50), declared = false), 1e-9)
    }

    private fun setupScore(fakeOut: Boolean, setup: Boolean, foeType: String = "normal",
        supportAbility: String = "none", stage: String = "special_attack", declared: Boolean = true): Double {
        val own = listOf(own(1, hp = 150, specialAttack = 380, speed = 250),
            own(2, hp = 80, specialAttack = 230, speed = 250))
        val support = listOf(attack()) + if (fakeOut) listOf(option("fakeout", BattleMoveCandidateView("normal",
            BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 3, 10, effects = effects(listOf(
                BattleMoveEffectView(BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY, BattleMoveEffectTarget.USER),
                BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0, valueId = "flinch")))))) else emptyList()
        val booster = listOf(attack()) + if (setup) listOf(option("nastyplot", BattleMoveCandidateView("dark",
            BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELF,
            effects(if (declared) listOf(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE,
                BattleMoveEffectTarget.USER, statStages = mapOf(stage to 2))) else emptyList())))) else emptyList()
        return score(context(own, listOf(support, booster), listOf(supportAbility, "none"), foeType, foeHp = 150))
    }

    private fun roomScore(enabled: Boolean, speeds: List<Int>, declared: Boolean = true): Double {
        val own = speeds.mapIndexed { index, speed -> own(index + 1L, hp = 400, specialAttack = 100, speed = speed) }
        val room = option("trickroom", BattleMoveCandidateView("psychic", BattleMoveDamageCategory.STATUS,
            0.0, 100.0, -7, 10, BattleMoveTargetPattern.ALL_ACTIVE,
            effects(if (declared) listOf(BattleMoveEffectView(BattleMoveEffectKind.FIELD_CONDITION,
                BattleMoveEffectTarget.FIELD, valueId = "trickroom")) else emptyList())))
        return score(context(own, listOf(listOf(attack()) + if (enabled) listOf(room) else emptyList(), listOf(attack())),
            listOf("none", "none"), "normal", foeHp = 400))
    }

    private fun score(context: BattleLeadChoiceContext) = LocalDoubleLeadPairEvaluation.scores(context,
        context.ownTeam, context.opponentTeamPreview.pokemon, detailed = true)
        .getValue(listOf(UUID(0, 1), UUID(0, 2)))

    private fun context(own: List<BattlePokemonStateView>, moves: List<List<BattlePublicMoveOptionView>>,
        abilities: List<String>, foeType: String, foeHp: Int): BattleLeadChoiceContext {
        val catalog = BattlePublicActionCatalogView(own.mapIndexed { index, pokemon ->
            BattlePokemonActionCatalogView(pokemon.battlePokemonId, moves[index], moveSetComplete = true)
        })
        val builds = BattleExactOwnTeamView(own.mapIndexed { index, pokemon ->
            BattleExactPokemonBuildView(pokemon.battlePokemonId, abilities[index], null, "serious", "N",
                EVS, EVS.mapValues { 31 }, null, pokemon.speciesId)
        })
        val preview = BattleOpponentTeamPreviewView(3, (0..2).map { slot ->
            BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:$foeType", null, 50, setOf(foeType),
                BattleCombatStatRangesView(BattleIntegerRange(foeHp - 1, foeHp + 1), BattleIntegerRange(99, 101),
                    BattleIntegerRange(99, 101), BattleIntegerRange(99, 101), BattleIntegerRange(99, 101),
                    BattleIntegerRange(149, 151), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        })
        return BattleLeadChoiceContext(BattleFormat.DOUBLE, own, catalog, builds, preview, BattleTrainerProfile.boss())
    }

    private fun attack() = option("darkpulse", BattleMoveCandidateView("dark", BattleMoveDamageCategory.SPECIAL,
        90.0, 100.0, 0, 10))

    private fun option(id: String, details: BattleMoveCandidateView) =
        BattlePublicMoveOptionView(id, details, BattlePublicMoveKnowledge.EXACT_OWN)

    private fun effects(values: List<BattleMoveEffectView>) =
        BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, values, scriptedBehavior = false)

    private fun own(id: Long, hp: Int, specialAttack: Int, speed: Int) = BattlePokemonStateView(UUID(0, id),
        BattleSide.ALLY, null, "cobblemon:normal", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false,
        setOf("normal"), BattleCombatStatRangesView.exact(hp, 100, 100, specialAttack, 100, speed))

    private companion object {
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
