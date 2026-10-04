package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalRepeatedMoveMechanics
import jbro.cobblemon.mcc.betterai.matchup.LocalFlinchAvailability
import jbro.cobblemon.mcc.betterai.matchup.MoveMatchupScore
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalMatchupMechanicalEvolutionTest {
    private val flinch = BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS,
        BattleMoveEffectTarget.SELECTED_TARGET, 0.3, "flinch")

    @Test
    fun `an unavailable source cannot impose the healthy flinch probability`() {
        val healthy = flinchReply(context(effects = listOf(flinch)))
        for (status in listOf("slp", "frz", "par")) {
            val impaired = flinchReply(context(actorStatus = status, effects = listOf(flinch)))
            assertTrue(impaired.damageWhileStandingByUses[1] > healthy.damageWhileStandingByUses[1], "$status: $healthy / $impaired")
        }
    }

    @Test
    fun `an exhausted flinch move does not keep removing later replies`() {
        val base = context(effects = listOf(flinch))
        val limited = base.copy(publicActionCatalog = BattlePublicActionCatalogView(base.publicActionCatalog.entries.map { entry ->
            if (entry.battlePokemonId != UUID(0, 1)) entry else BattlePokemonActionCatalogView(entry.battlePokemonId,
                entry.moves.map { it.copy(details = it.details.copy(currentPp = 1)) }, entry.moveSetComplete)
        }))
        val reply = flinchReply(limited)
        assertTrue(reply.damageWhileStandingByUses[2] - reply.damageWhileStandingByUses[1] > reply.damageWhileStandingByUses[1], reply.toString())
    }

    @Test
    fun `flinch begins after a blocking Substitute breaks`() {
        val base = context(allyPower = 100.0, effects = listOf(flinch))
        val positioned = base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.OPPONENT) it.copyState(knownVolatileEffectIds = setOf("substitute", "substitutehp:0.2")) else it
        }))
        val reply = flinchReply(positioned)
        assertTrue(reply.damageWhileStandingByUses[2] - reply.damageWhileStandingByUses[1] < reply.damageWhileStandingByUses[1] * 0.9, reply.toString())
    }

    @Test
    fun `a type immune recipient cannot flinch from a nullified attack`() {
        val base = context(effects = listOf(flinch))
        val immune = base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.OPPONENT) it.copyState(knownTypeIds = setOf("ghost")) else it
        }))
        val table = LocalMatchupScoreCalculator.calculate(immune)
        assertEquals(table.moves(UUID(0, 2), UUID(0, 1)).first().damageWhileStandingByUses[1],
            flinchReply(immune).damageWhileStandingByUses[1], 1e-12)
    }

    @Test
    fun `contact recoil can stop the attacker before its repeated knockout`() {
        val base = context(targetItem = "rockyhelmet", mechanicFlags = setOf("contact"))
        val low = base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(hpFraction = 0.2) else it
        }))
        assertTrue(score(low).expectedHitsToKnockout.isInfinite(), score(low).toString())
    }

    @Test
    fun `contact burn and defensive hit reactions enter the repeated damage profile`() {
        val ordinary = score(context(allyPower = 60.0, mechanicFlags = setOf("contact")))
        val burned = score(context(allyPower = 60.0, targetAbility = "flamebody", mechanicFlags = setOf("contact")))
        assertTrue(burned.expectedHitsToKnockout > ordinary.expectedHitsToKnockout, "$ordinary / $burned")
        val shell = score(context(allyPower = 60.0, targetAbility = "angershell"))
        assertTrue(shell.expectedHitsToKnockout < ordinary.expectedHitsToKnockout, "$ordinary / $shell")
    }

    private fun flinchReply(position: BattleDecisionContext): MoveMatchupScore {
        val table = LocalMatchupScoreCalculator.calculate(position)
        fun selected(side: BattleSide): LocalMatchupScoreCalculator.ScoredMove {
            val user = position.state.pokemon.first { it.side == side }
            val target = position.state.pokemon.first { it.side != side }
            val action = PublicFutureActionFactory.primitiveActionsForPokemon(position.state, side, user.battlePokemonId,
                position.publicActionCatalog).first { it.kind == BattleActionKind.USE_MOVE }
            return LocalMatchupScoreCalculator.ScoredMove(table.moves(user.battlePokemonId, target.battlePokemonId).first(), action)
        }
        return requireNotNull(LocalFlinchAvailability.profile(position, selected(BattleSide.ALLY), selected(BattleSide.OPPONENT), 1.0))
    }

    @Test
    fun `flinch availability follows public Substitute bypass ability and sound flags`() {
        val flinch = BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 0.3, "flinch")
        for ((ability, flags) in listOf("infiltrator" to emptySet(), null to setOf("sound"))) {
            fun positioned(effects: List<BattleMoveEffectView>): BattleDecisionContext {
                val base = context(allyPower = 65.0, foePower = 90.0, allySpeed = 150, actorAbility = ability,
                    effects = effects, mechanicFlags = flags)
                return base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                    if (it.side == BattleSide.OPPONENT) it.copyState(knownVolatileEffectIds = setOf("substitute", "substitutehp:0.25")) else it
                }))
            }
            val ordinary = LocalMatchupScoreCalculator.calculate(positioned(emptyList())).pokemon(UUID(0, 1), UUID(0, 2))!!
            val flinching = LocalMatchupScoreCalculator.calculate(positioned(listOf(flinch))).pokemon(UUID(0, 1), UUID(0, 2))!!
            assertTrue(flinching.winProbability > ordinary.winProbability, "$ability $flags: $ordinary / $flinching")
        }
    }

    @Test
    fun `a defensive pinch berry changes later repeated damage without adding a score bonus`() {
        fun positioned(item: String?): BattleDecisionContext {
            val base = context(allyPower = 60.0, targetItem = item)
            return base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
                if (it.side == BattleSide.OPPONENT) it.copyState(hpFraction = 0.65) else it
            }))
        }
        val plain = score(positioned(null))
        val berry = score(positioned("ganlonberry"))
        assertTrue(berry.expectedHitsToKnockout > plain.expectedHitsToKnockout, "$plain / $berry")
    }

    @Test
    fun `a public opponent move hypothesis retains its first and later attacks during mechanical evolution`() {
        val profile = hypotheticalProfile(pp = 10)
        assertTrue(profile.damageWhileStanding[1] > 0.1, "The existing assumed opponent attack must not become WAIT: ${profile.damageWhileStanding}")
        assertTrue(profile.damageWhileStanding[2] > profile.damageWhileStanding[1])
    }

    @Test
    fun `a preserved hypothetical attack still spends its public assumed PP`() {
        val profile = hypotheticalProfile(pp = 1)
        assertTrue(profile.damageWhileStanding[1] > 0.1)
        assertEquals(profile.damageWhileStanding[1], profile.damageWhileStanding[2], 1e-12)
    }

    private fun hypotheticalProfile(pp: Int): jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutProfile {
        val original = context()
        val foe = original.state.pokemon.first { it.side == BattleSide.OPPONENT }
        val details = original.publicActionCatalog.forPokemon(foe.battlePokemonId).single().details.copy(currentPp = pp)
        val pool = BattlePublicMoveCandidatePoolView(foe.battlePokemonId, foe.speciesId, foe.formId,
            setOf("probe"), "fixture:public_learnset", mapOf("probe" to details))
        val catalog = BattlePublicActionCatalogView(original.publicActionCatalog.entries.map {
            if (it.battlePokemonId == foe.battlePokemonId) BattlePokemonActionCatalogView(it.battlePokemonId, emptyList(), false) else it
        }, candidatePools = listOf(pool))
        val position = original.copy(publicActionCatalog = catalog, state = original.state.derive(field = BattleFieldStateView(
            BattleTimedEffectView("raindance", 5), null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() })))
        val action = PublicFutureActionFactory.slotActions(position.state, BattleSide.OPPONENT, catalog, includeMoveHypotheses = true)
            .first { it.kind == BattleActionKind.USE_MOVE }
        val rolls = requireNotNull(PublicBattleTacticalCalculator.conservativeDamageRollFractions(action, position, BattleSide.OPPONENT))
        return requireNotNull(LocalRepeatedMoveMechanics.profile(position, action, foe.battlePokemonId, UUID(0, 1), rolls, 1.0,
            LocalProjectedActionCalculationCache()))
    }

    @Test
    fun `a faster flinch move reduces the opponents available attacks without a new score bonus`() {
        val base = context(allyPower = 65.0, foePower = 90.0, allySpeed = 150)
        val flinching = context(allyPower = 65.0, foePower = 90.0, allySpeed = 150, effects = listOf(
            BattleMoveEffectView(BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 0.3, "flinch")))
        val ordinary = LocalMatchupScoreCalculator.calculate(base).pokemon(UUID(0, 1), UUID(0, 2))!!
        val improved = LocalMatchupScoreCalculator.calculate(flinching).pokemon(UUID(0, 1), UUID(0, 2))!!
        assertTrue(improved.winProbability > ordinary.winProbability, "$ordinary / $improved")
    }

    @Test
    fun `a publicly held Sitrus Berry delays the repeated hit knockout`() {
        val plain = score(context())
        val berry = score(context(targetItem = "sitrusberry"))
        assertTrue(berry.expectedHitsToKnockout > plain.expectedHitsToKnockout, "$plain / $berry")
    }

    @Test
    fun `a sleeping and a paralysed attacker spend actual unavailable turns`() {
        val normal = score(context())
        assertTrue(score(context(actorStatus = "slp")).expectedHitsToKnockout > normal.expectedHitsToKnockout)
        assertTrue(score(context(actorStatus = "par")).expectedHitsToKnockout > normal.expectedHitsToKnockout)
    }

    @Test
    fun `a user stat drop changes the damage of the next use`() {
        val drop = BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER,
            statStages = mapOf("special_attack" to -2))
        val ordinary = score(context(category = BattleMoveDamageCategory.SPECIAL))
        val repeated = score(context(category = BattleMoveDamageCategory.SPECIAL, effects = listOf(drop)))
        assertTrue(repeated.expectedHitsToKnockout > ordinary.expectedHitsToKnockout, "$ordinary / $repeated")
        val contrary = score(context(category = BattleMoveDamageCategory.SPECIAL, effects = listOf(drop), actorAbility = "contrary"))
        assertTrue(contrary.expectedHitsToKnockout < repeated.expectedHitsToKnockout)
    }

    @Test
    fun `residual poison can finish a target and Leftovers can postpone it`() {
        val normal = score(context())
        assertTrue(score(context(targetStatus = "tox")).expectedHitsToKnockout < normal.expectedHitsToKnockout)
        assertTrue(score(context(targetItem = "leftovers")).expectedHitsToKnockout > normal.expectedHitsToKnockout)
    }

    @Test
    fun `a publicly known Substitute absorbs a use instead of counting as damage on its holder`() {
        val context = context()
        val substituted = context.copy(state = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.side == BattleSide.OPPONENT) it.copyState(knownVolatileEffectIds = setOf("substitute", "substitutehp:0.25")) else it
        }))
        assertTrue(score(substituted).expectedHitsToKnockout > score(context).expectedHitsToKnockout)
    }

    private fun score(context: BattleDecisionContext) = LocalMatchupScoreCalculator.calculate(context)
        .moves(UUID(0, 1), UUID(0, 2)).first()

    private fun context(
        actorStatus: String? = null, targetStatus: String? = null, targetItem: String? = null,
        actorAbility: String? = null, targetAbility: String? = null, category: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        effects: List<BattleMoveEffectView> = emptyList(),
        mechanicFlags: Set<String> = emptySet(),
        allyPower: Double = 80.0, foePower: Double = 80.0, allySpeed: Int = 100,
    ): BattleDecisionContext {
        val pokemon = listOf(mon(BattleSide.ALLY, actorStatus, ability = actorAbility, speed = allySpeed), mon(BattleSide.OPPONENT, targetStatus, targetItem, targetAbility))
        val state = BattleStateView(UUID(0, 803), BattleFormat.SINGLE, 3, pokemon, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val details = BattleMoveCandidateView("normal", category, 80.0, 100.0, 0, 10,
            effects = if (effects.isEmpty() && mechanicFlags.isEmpty()) null else
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, effects, false, mechanicFlags = mechanicFlags))
        return BattleDecisionContext(UUID(0, 804), state, listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)),
            Long.MAX_VALUE, publicActionCatalog = BattlePublicActionCatalogView(pokemon.map {
                BattlePokemonActionCatalogView(it.battlePokemonId, listOf(BattlePublicMoveOptionView("probe", details.copy(
                    power = if (it.side == BattleSide.ALLY) allyPower else foePower,
                    effects = if (it.side == BattleSide.ALLY) details.effects else null),
                    if (it.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true)
            }))
    }

    private fun mon(side: BattleSide, status: String?, item: String? = null, ability: String? = null, speed: Int = 100) =
        BattlePokemonStateView(UUID(0, side.ordinal.toLong() + 1), side, 0, "fixture:matchup", null, 50, 1.0,
            status, emptyMap(), emptySet(), ability, item, false, knownTypeIds = setOf("normal"),
            combatStats = BattleCombatStatRangesView(BattleIntegerRange(200, 200), BattleIntegerRange(120, 120),
                BattleIntegerRange(100, 100), BattleIntegerRange(120, 120), BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
                    else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
