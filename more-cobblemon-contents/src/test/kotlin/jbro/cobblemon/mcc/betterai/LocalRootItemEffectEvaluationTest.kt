package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.mechanics.LocalAfterHitReactions
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Root fallback must price the publicly observable item effect as well as the move's damage. */
class LocalRootItemEffectEvaluationTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `Knock Off values the recovery actually removed from a surviving target`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "leftovers")
        val knockOff = attack("knockoff")
        val ordinary = attack("feintattack")
        val context = context(attacker, target, knockOff, ordinary)
        assertTrue(LocalTacticalScorer.score(knockOff, context) > LocalTacticalScorer.score(ordinary, context) + 1.0)
    }

    @Test
    fun `Knock Off gives no removal credit through Sticky Hold or a Substitute`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        listOf(
            fixture.mon(BattleSide.OPPONENT, 0, ability = "stickyhold"),
            fixture.mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute")),
        ).forEach { target ->
            val held = target.copyState(knownHeldItemId = "leftovers")
            val knockOff = attack("knockoff")
            val ordinary = attack("feintattack")
            val context = context(attacker, held, knockOff, ordinary)
            assertEquals(LocalTacticalScorer.score(ordinary, context), LocalTacticalScorer.score(knockOff, context), 1e-9)
        }
    }

    @Test
    fun `Knock Off gives no future recovery credit for a knocked out holder or an unknown item`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        listOf(
            fixture.mon(BattleSide.OPPONENT, 0, hp = 0.1).copyState(knownHeldItemId = "leftovers"),
            fixture.mon(BattleSide.OPPONENT, 0),
        ).forEach { target ->
            val knockOff = attack("knockoff")
            val ordinary = attack("feintattack")
            val context = context(attacker, target, knockOff, ordinary)
            assertEquals(LocalTacticalScorer.score(ordinary, context), LocalTacticalScorer.score(knockOff, context), 1e-9)
        }
    }

    @Test
    fun `Trick can acquire a public item while its user holds none`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, hp = 0.5).copyState(knownHeldItemId = "")
        val target = fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5).copyState(knownHeldItemId = "leftovers")
        val trick = status("trick")
        assertTrue(LocalNonDamagingMoveEvaluator.pressure(trick, context(attacker, target, trick), 1.0) > 0.0)
    }

    @Test
    fun `Trick transferring beneficial recovery to the opponent has negative value`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, hp = 0.5).copyState(knownHeldItemId = "leftovers")
        val target = fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5).copyState(knownHeldItemId = "")
        val trick = status("trick")
        assertTrue(LocalNonDamagingMoveEvaluator.pressure(trick, context(attacker, target, trick), 1.0) < 0.0)
    }

    @Test
    fun `Trick does not invent an item benefit for unknown or identical held items`() {
        val trick = status("trick")
        listOf(null, "leftovers").forEach { item ->
            val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = item)
            val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = item)
            assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, context(attacker, target, trick), 1.0), 1e-9)
        }
    }

    @Test
    fun `Trick is valued by the public attacking role of each Choice Band recipient`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "choiceband")
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "")
        val trick = status("trick")
        val physical = attack("tackle", power = 40.0)
        val special = attack("ember", power = 40.0, special = true)
        val usefulToUs = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to physical, target to special))
        val usefulToNeither = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to special, target to special))
        assertTrue(LocalNonDamagingMoveEvaluator.pressure(trick, usefulToUs, 1.0) < 0.0)
        assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, usefulToUs, 0.0), 1e-9)
        assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, usefulToNeither, 1.0), 1e-9)
    }

    @Test
    fun `Trick has no root effect through Sticky Hold or Substitute`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, hp = 0.5).copyState(knownHeldItemId = "leftovers")
        val trick = status("trick")
        listOf(
            fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5, ability = "stickyhold"),
            fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5, volatiles = setOf("substitute")),
        ).forEach { target ->
            val empty = target.copyState(knownHeldItemId = "")
            assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, context(attacker, empty, trick), 1.0), 1e-9)
        }
    }

    @Test
    fun `a surviving public Weakness Policy holder lowers the triggering attack score`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, type = "dark")
        val target = fixture.mon(BattleSide.OPPONENT, 0, type = "psychic")
        val attack = attack("bite", type = "dark")
        val reply = attack("tackle", power = 40.0)
        fun score(item: String): Double {
            val holder = target.copyState(knownHeldItemId = item)
            return LocalTacticalScorer.score(attack, context(attacker, holder, attack).copy(publicActionCatalog = catalog(holder to reply)))
        }
        assertTrue(score("weaknesspolicy") < score("") - 1.0)
        val policy = target.copyState(knownHeldItemId = "weaknesspolicy")
        val scored = LocalTacticalScorer.scoreBreakdown(attack, context(attacker, policy, attack).copy(publicActionCatalog = catalog(policy to reply)))
        assertTrue(scored.statStageUtility < 0.0, "search must replace the reaction stage value rather than count it twice")
    }

    @Test
    fun `Weakness Policy gives no reaction credit for a knockout Substitute or neutral hit`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        val reply = attack("tackle", power = 40.0)
        listOf(
            fixture.mon(BattleSide.OPPONENT, 0, type = "psychic", hp = 0.1),
            fixture.mon(BattleSide.OPPONENT, 0, type = "psychic", volatiles = setOf("substitute")),
            fixture.mon(BattleSide.OPPONENT, 0, type = "normal"),
        ).forEach { target ->
            val attack = attack("bite", type = "dark")
            fun score(item: String): Double {
                val holder = target.copyState(knownHeldItemId = item)
                return LocalTacticalScorer.score(attack, context(attacker, holder, attack).copy(publicActionCatalog = catalog(holder to reply)))
            }
            assertEquals(score(""), score("weaknesspolicy"), 1e-9)
        }
    }

    @Test
    fun `White Herb prevents the root penalty for self inflicted stat drops`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "whiteherb")
        val target = fixture.mon(BattleSide.OPPONENT, 0)
        val dropping = attack("closecombat", effects = mapOf("defence" to -1, "special_defence" to -1))
        val ordinary = attack("closecombat")
        val context = context(attacker, target, dropping, ordinary)
        assertEquals(LocalTacticalScorer.score(ordinary, context), LocalTacticalScorer.score(dropping, context), 1e-9)
        assertEquals(0.0, LocalTacticalScorer.scoreBreakdown(dropping, context).statStageUtility, 1e-9)
    }

    @Test
    fun `a suppressed White Herb cannot erase a root stat drop`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, ability = "klutz").copyState(knownHeldItemId = "whiteherb")
        val target = fixture.mon(BattleSide.OPPONENT, 0)
        val dropping = attack("closecombat", effects = mapOf("defence" to -1, "special_defence" to -1))
        val ordinary = attack("closecombat")
        val context = context(attacker, target, dropping, ordinary)
        assertTrue(LocalTacticalScorer.score(dropping, context) < LocalTacticalScorer.score(ordinary, context))
    }

    @Test
    fun `Trick cannot remove an owner's Mega Stone Plate or signature item`() {
        listOf(
            "charizard" to "charizarditex",
            "arceus" to "splashplate",
            "zacian" to "rustedsword",
            "ogerpon" to "wellspringmask",
            "ironhands" to "boosterenergy",
        ).forEach { (species, item) ->
            val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "leftovers")
            val target = speciesMon(BattleSide.OPPONENT, species, item)
            val trick = status("trick")
            val context = context(attacker, target, trick)
            val projected = PublicSingleTurnProjector.project(context.state, trick, BattleActionCandidate("wait", BattleActionKind.WAIT), context)
            assertTrue(projected.isNotEmpty())
            projected.forEach {
                assertEquals(item, it.stateBeforeResidual.pokemon.single { mon -> mon.battlePokemonId == target.battlePokemonId }.knownHeldItemId, species)
            }
            assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, context, 1.0), 1e-9, species)
        }
    }

    @Test
    fun `Trick cannot give a Plate to Arceus or a rusted item to its signature recipient`() {
        listOf("arceus" to "splashplate", "zacian" to "rustedsword", "charizard" to "charizarditex", "ironhands" to "boosterenergy").forEach { (species, item) ->
            val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = item)
            val target = speciesMon(BattleSide.OPPONENT, species, "leftovers")
            val trick = status("trick")
            val context = context(attacker, target, trick)
            PublicSingleTurnProjector.project(context.state, trick, BattleActionCandidate("wait", BattleActionKind.WAIT), context).forEach {
                assertEquals("leftovers", it.stateBeforeResidual.pokemon.single { mon -> mon.battlePokemonId == target.battlePokemonId }.knownHeldItemId, species)
            }
        }
    }

    @Test
    fun `a Plate or unrelated Mega Stone remains transferable between ordinary species`() {
        listOf("splashplate", "charizarditex").forEach { item ->
            val attacker = speciesMon(BattleSide.ALLY, "pikachu", "leftovers")
            val target = speciesMon(BattleSide.OPPONENT, "eevee", item)
            val trick = status("trick")
            val context = context(attacker, target, trick)
            val projected = PublicSingleTurnProjector.project(context.state, trick, BattleActionCandidate("wait", BattleActionKind.WAIT), context)
            assertTrue(projected.isNotEmpty())
            projected.forEach {
                assertEquals(item, it.stateBeforeResidual.pokemon.single { mon -> mon.battlePokemonId == attacker.battlePokemonId }.knownHeldItemId)
            }
        }
    }

    @Test
    fun `one ply replaces the root item residual value exactly once`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0, hp = 0.5).copyState(knownHeldItemId = "leftovers")
        val target = fixture.mon(BattleSide.OPPONENT, 0, hp = 0.5).copyState(knownHeldItemId = "")
        val trick = status("trick")
        val source = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to trick, target to status("splash")))
        val profile = BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY)
        val tuning = LocalDecisionTuning.CURRENT.copy(searchAuthority = 0.0, leafPressureWeight = 0.0,
            leafDuelValue = 0.0, leafPersistentStageValue = 0.0, leafTeamCoverageWeight = 0.0,
            leafMatchupTeamWeight = 0.0, leafMatchupFieldWeight = 0.0)
        val ranks = LocalBattleActionPolicy.rank(source, null, profile, tuning)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranks, source, profile, tuning, clockMillis = { 0L },
            budget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier).copy(timeMillis = 10_000L),
            moveUsageForFormat = { null })
        assertEquals(1, result.depthCompleted)
        // Root compares retained vs transferred Leftovers (-12.5). The complete turn gives the
        // opponent +6.25 HP. Replacing the root copy therefore adds +6.25, leaving -6.25 once.
        assertEquals(6.25, result.ranked.single().lookaheadUtility, 1e-9)
        assertEquals(-6.25, result.ranked.single().comparisonValue - ranks.single().comparisonValue + ranks.single().outcome.tacticalUtility, 1e-9)
    }

    @Test
    fun `search preserves public item attack pressure which immediate projection does not price`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "choiceband")
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "")
        val trick = status("trick")
        val source = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to attack("tackle", power = 40.0), target to status("splash")))
        val profile = BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY)
        val tuning = LocalDecisionTuning.CURRENT.copy(searchAuthority = 0.0, leafPressureWeight = 0.0,
            leafDuelValue = 0.0, leafPersistentStageValue = 0.0, leafTeamCoverageWeight = 0.0,
            leafMatchupTeamWeight = 0.0, leafMatchupFieldWeight = 0.0)
        val ranks = LocalBattleActionPolicy.rank(source, null, profile, tuning)
        assertTrue(ranks.single().outcome.tacticalUtility < 0.0)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranks, source, profile, tuning, clockMillis = { 0L },
            budget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier).copy(timeMillis = 10_000L),
            moveUsageForFormat = { null })
        assertEquals(1, result.depthCompleted)
        assertEquals(0.0, result.ranked.single().lookaheadUtility, 1e-9)
        assertEquals(ranks.single().comparisonValue, result.ranked.single().comparisonValue, 1e-9)
    }

    @Test
    fun `super effective fixed damage does not get a root Weakness Policy activation penalty`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        val target = fixture.mon(BattleSide.OPPONENT, 0, type = "psychic")
        val fixedDamage = attack("nightshade", type = "ghost", power = 0.0, special = true)
        fun score(item: String): Double {
            val holder = target.copyState(knownHeldItemId = item)
            val source = context(attacker, holder, fixedDamage).copy(publicActionCatalog = catalog(holder to attack("tackle")))
            return LocalTacticalScorer.score(fixedDamage, source)
        }
        assertEquals(score(""), score("weaknesspolicy"), 1e-9)
    }

    @Test
    fun `a fixed damage body hit leaves Weakness Policy unspent and stages unchanged`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0)
        val target = fixture.mon(BattleSide.OPPONENT, 0, type = "psychic").copyState(knownHeldItemId = "weaknesspolicy")
        val before = fixture.state(attacker, target)
        val hit = before.copyState(pokemon = listOf(attacker, target.copyState(hpFraction = 0.8)))
        val after = LocalAfterHitReactions.apply(before, hit, attacker.battlePokemonId, target.battlePokemonId,
            attack("nightshade", type = "ghost", power = 0.0, special = true), 0.2)
        val holder = after.pokemon.single { it.battlePokemonId == target.battlePokemonId }
        assertEquals("weaknesspolicy", holder.knownHeldItemId)
        assertEquals(emptyMap<String, Int>(), holder.statStages)
    }

    @Test
    fun `Trick values an acquired White Herb restoring the user's public negative Attack`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "", statStages = mapOf("attack" to -2))
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "whiteherb")
        val trick = status("trick")
        val source = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to attack("tackle"), target to status("splash")))
        assertTrue(LocalNonDamagingMoveEvaluator.pressure(trick, source, 1.0) > 1.0)
    }

    @Test
    fun `the White Herb received by Trick immediately clears negative stages and is consumed`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "", statStages = mapOf("attack" to -2))
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "whiteherb")
        val trick = status("trick")
        val source = context(attacker, target, trick)
        val outcomes = PublicSingleTurnProjector.project(source.state, trick, BattleActionCandidate("wait", BattleActionKind.WAIT), source)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach {
            val holder = it.stateBeforeResidual.pokemon.single { mon -> mon.battlePokemonId == attacker.battlePokemonId }
            assertEquals(0, holder.statStages["attack"])
            assertEquals("", holder.knownHeldItemId)
        }
    }

    @Test
    fun `search replaces the acquired White Herb stage value exactly once`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "", statStages = mapOf("attack" to -2))
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "whiteherb")
        val trick = status("trick")
        val source = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to attack("tackle"), target to status("splash")))
        val profile = BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY)
        val tuning = LocalDecisionTuning.CURRENT.copy(searchAuthority = 0.0, leafPressureWeight = 0.0,
            leafDuelValue = 0.0, leafPersistentStageValue = 0.0, leafTeamCoverageWeight = 0.0,
            leafMatchupTeamWeight = 0.0, leafMatchupFieldWeight = 0.0)
        val ranks = LocalBattleActionPolicy.rank(source, null, profile, tuning)
        assertTrue(ranks.single().outcome.itemUtility > 1.0)
        val result = LocalRecursiveLookaheadEvaluator.evaluate(ranks, source, profile, tuning, clockMillis = { 0L },
            budget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier).copy(timeMillis = 10_000L),
            moveUsageForFormat = { null })
        assertEquals(1, result.depthCompleted)
        assertEquals(0.0, result.ranked.single().lookaheadUtility, 1e-9)
        assertEquals(ranks.single().comparisonValue, result.ranked.single().comparisonValue, 1e-9)
    }

    @Test
    fun `Klutz keeps an acquired White Herb dormant without restoring stages`() {
        val attacker = fixture.mon(BattleSide.ALLY, 0).copyState(knownHeldItemId = "", knownAbilityId = "klutz", statStages = mapOf("attack" to -2))
        val target = fixture.mon(BattleSide.OPPONENT, 0).copyState(knownHeldItemId = "whiteherb")
        val trick = status("trick")
        val source = context(attacker, target, trick).copy(publicActionCatalog = catalog(attacker to attack("tackle"), target to status("splash")))
        assertEquals(0.0, LocalNonDamagingMoveEvaluator.pressure(trick, source, 1.0), 1e-9)
        val outcomes = PublicSingleTurnProjector.project(source.state, trick, BattleActionCandidate("wait", BattleActionKind.WAIT), source)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach {
            val holder = it.stateBeforeResidual.pokemon.single { mon -> mon.battlePokemonId == attacker.battlePokemonId }
            assertEquals(-2, holder.statStages["attack"])
            assertEquals("whiteherb", holder.knownHeldItemId)
        }
    }

    private fun context(actor: BattlePokemonStateView, target: BattlePokemonStateView, vararg actions: BattleActionCandidate) =
        fixture.context(fixture.state(actor, target), *actions)

    private fun attack(id: String, type: String = "dark", power: Double = 40.0, special: Boolean = false,
        effects: Map<String, Int> = emptyMap()) = BattleActionCandidate(
        "$id:${effects.hashCode()}", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(type, if (special) BattleMoveDamageCategory.SPECIAL else BattleMoveDamageCategory.PHYSICAL,
            power, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = effects.takeIf { it.isNotEmpty() }?.let {
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(
                    BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, 1.0, statStages = it)), false)
            }),
        facts = BattleCandidateFactsView(baseAccuracyProbability = 1.0,
            standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
            standardDamageFractionRange = BattleDamageFractionRange(0.2, 0.2),
            standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
            standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0)),
    )

    private fun status(id: String) = BattleActionCandidate(
        id, BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("psychic", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT),
    )

    private fun catalog(vararg options: Pair<BattlePokemonStateView, BattleActionCandidate>) = BattlePublicActionCatalogView(options.map { (mon, action) ->
        BattlePokemonActionCatalogView(mon.battlePokemonId, listOf(BattlePublicMoveOptionView(requireNotNull(action.moveId), requireNotNull(action.moveDetails),
            BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
    })

    private fun speciesMon(side: BattleSide, species: String, item: String) = BattlePokemonStateView(
        battlePokemonId = java.util.UUID.randomUUID(), side = side, activeSlot = 0, speciesId = "cobblemon:$species", formId = null,
        level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = item, fainted = false, knownTypeIds = setOf("normal"), combatStats = fixture.mon(side, 0).combatStats,
    )
}
