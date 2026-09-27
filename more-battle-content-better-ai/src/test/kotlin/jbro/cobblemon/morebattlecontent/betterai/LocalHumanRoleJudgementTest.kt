package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalMechanicOptionValue
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalStatusTargetFit
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalHumanRoleJudgementTest {
    // ---- Own Pokemon role ----

    @Test
    fun `the sole answer to the main threat outweighs an ally with nothing to answer, within a narrow band`() {
        val band = LocalOpponentThreat.roleBand(BattleTrainerTier.BOSS)
        val weights = LocalOpponentThreat.roleWeights(
            threats = mapOf(FOE_A to 1.0, FOE_B to 0.2),
            answersByFoe = mapOf(FOE_A to setOf(ACE), FOE_B to setOf(ACE, SPARE)),
            allyIds = listOf(ACE, SPARE, IDLE),
            band = band,
            confidence = 1.0,
        )

        assertTrue(weights.getValue(ACE) > weights.getValue(SPARE), "$weights")
        assertTrue(weights.getValue(SPARE) > weights.getValue(IDLE) || weights.getValue(SPARE) == band.minimum, "$weights")
        assertTrue(weights.values.all { it in band.minimum..band.maximum }, "$weights")
        assertTrue(band.maximum - band.minimum < LocalOpponentThreat.band(BattleTrainerTier.BOSS).let { it.maximum - it.minimum })
    }

    @Test
    fun `an unsure role judgement stays close to neutral`() {
        val sure = roleWeights(confidence = 1.0)
        val unsure = roleWeights(confidence = 0.25)

        assertEquals(1.0 + (sure.getValue(ACE) - 1.0) * 0.25, unsure.getValue(ACE), 1e-12)
        assertTrue(LocalOpponentThreat.roleWeights(mapOf(FOE_A to 1.0), mapOf(FOE_A to setOf(ACE)),
            listOf(ACE, IDLE), LocalOpponentThreat.roleBand(BattleTrainerTier.BOSS), 0.0).isEmpty())
    }

    @Test
    fun `lower tiers do not weigh their own Pokemon`() {
        listOf(BattleTrainerTier.INTRODUCTORY, BattleTrainerTier.STANDARD).forEach { tier ->
            val band = LocalOpponentThreat.roleBand(tier)
            assertEquals(band.minimum, band.maximum)
        }
    }

    @Test
    fun `losing a key ally lowers the AI-side adjustment and losing an idle one raises it`() {
        val weights = mapOf(ACE to 1.2, IDLE to 0.85)
        val before = rolesState()
        val aceLost = LocalOpponentThreat.materialAdjustment(before.withHp(ACE, 0.0), weights) -
            LocalOpponentThreat.materialAdjustment(before, weights)
        val idleLost = LocalOpponentThreat.materialAdjustment(before.withHp(IDLE, 0.0), weights) -
            LocalOpponentThreat.materialAdjustment(before, weights)

        assertTrue(aceLost < 0.0 && idleLost > 0.0, "ace=$aceLost idle=$idleLost")
        assertTrue(kotlin.math.abs(aceLost) < 1.0, "A role is worth less than one HP bar plus survival: $aceLost")
    }

    // ---- Tera carrier tilt ----

    @Test
    fun `the clear Tera carrier pays less for its Tera the more its trainer sticks to plans`() {
        val context = teraContext(active = GROUND, activeTera = "water", bench = BUG, benchTera = "normal")
        assertTrue(cost(context, planPersistence = 1.0) < cost(context, planPersistence = 0.0),
            "${cost(context, 1.0)} vs ${cost(context, 0.0)}")
    }

    @Test
    fun `a non-carrier pays more for its Tera, by at most thirty percent`() {
        val context = teraContext(active = BUG, activeTera = "normal", bench = GROUND, benchTera = "water")
        val hoarding = cost(context, planPersistence = 1.0)
        val neutral = cost(context, planPersistence = 0.0)

        assertTrue(hoarding > neutral && hoarding <= neutral * 1.3 + 1e-9, "hoarding=$hoarding neutral=$neutral")
    }

    @Test
    fun `no carrier is picked when two allies fit Tera about equally`() {
        val context = teraContext(active = BUG, activeTera = "water", bench = GROUND, benchTera = "water")
        assertEquals(cost(context, planPersistence = 0.0), cost(context, planPersistence = 1.0), 1e-9)
    }

    // ---- Status target fit ----

    @Test
    fun `burn suits a physical attacker and paralysis a faster one`() {
        val context = statusContext()
        val physical = target(attack = 150, special = 60, speed = 80)
        val special = target(attack = 60, special = 150, speed = 80)
        val fast = target(attack = 100, special = 100, speed = 200)
        val slow = target(attack = 100, special = 100, speed = 20)

        assertTrue(fit("brn", physical, context) > 1.0 && fit("brn", special, context) < 1.0)
        assertTrue(fit("par", fast, context) > fit("par", slow, context))
        assertTrue(listOf(physical, special, fast, slow).all { fit("brn", it, context) in 0.6..1.4 })
    }

    @Test
    fun `a revealed Guts or Poison Heal makes the status worthless`() {
        val context = statusContext()
        assertEquals(0.0, fit("brn", target(ability = "guts"), context))
        assertEquals(0.0, fit("tox", target(ability = "poisonheal"), context))
        assertEquals(1.4, fit("par", target(ability = "poisonheal", speed = 200), context), 1e-9)
    }

    @Test
    fun `an introductory trainer does not match status to target and standard does it halfway`() {
        val context = statusContext()
        val guts = target(ability = "guts")
        assertEquals(1.0, LocalStatusTargetFit.multiplier("brn", guts, context,
            LocalStatusTargetFit.scale(BattleTrainerTier.INTRODUCTORY)))
        assertEquals(0.5, LocalStatusTargetFit.multiplier("brn", guts, context,
            LocalStatusTargetFit.scale(BattleTrainerTier.STANDARD)), 1e-12)
    }

    // ---- Accuracy with a knockout on the table ----

    @Test
    fun `a desperate risk-loving trainer still takes the sure knockout over the shaky one`() {
        val sure = attack("sure", accuracy = 100.0)
        val shaky = attack("shaky", accuracy = 80.0)
        val calculated = PublicBattleTacticalCalculator.calculate(knockoutContext(listOf(shaky, sure)))
        val risky = BattleTrainerProfile.boss().let {
            it.copy(personality = it.personality.copy(riskTolerance = 1.0, aggression = 1.0, caution = 0.0))
        }
        val ranked = LocalBattleActionPolicy.rank(calculated, null, risky)

        assertEquals("sure", ranked.first().outcome.candidate.actionId, ranked.joinToString { it.outcome.candidate.actionId })
        val selector = LocalWeightedActionSelector()
        val picks = (0L until 200L).map { seed -> selector.choose(ranked, seed, riskTolerance = 1.0).rank.outcome.candidate.actionId }
        assertTrue(picks.all { it == "sure" }, "shaky picked ${picks.count { it == "shaky" }} of 200")
    }

    // ---- fixtures ----

    private fun roleWeights(confidence: Double) = LocalOpponentThreat.roleWeights(
        threats = mapOf(FOE_A to 1.0, FOE_B to 0.2),
        answersByFoe = mapOf(FOE_A to setOf(ACE), FOE_B to setOf(ACE, SPARE)),
        allyIds = listOf(ACE, SPARE, IDLE),
        band = LocalOpponentThreat.roleBand(BattleTrainerTier.BOSS),
        confidence = confidence,
    )

    private fun cost(context: BattleDecisionContext, planPersistence: Double): Double {
        val boss = BattleTrainerProfile.boss()
        val profile = boss.copy(personality = boss.personality.copy(planPersistence = planPersistence))
        return LocalMechanicOptionValue.cost(context.candidates.single(), context, profile)
    }

    private fun teraContext(active: Set<String>, activeTera: String, bench: Set<String>, benchTera: String): BattleDecisionContext {
        val state = state(listOf(
            pokemon(ACE, BattleSide.ALLY, 0, active),
            pokemon(SPARE, BattleSide.ALLY, null, bench),
            pokemon(FOE_A, BattleSide.OPPONENT, 0, setOf("fire")),
            pokemon(FOE_B, BattleSide.OPPONENT, null, setOf("water")),
        ))
        val tera = BattleActionCandidate(
            actionId = "tera", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "tackle",
            mechanic = BattleMechanicCandidate("cobblemon:terastallize", null, null, transformedActorTypeIds = setOf(activeTera)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10),
        )
        return BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("tera-carrier".toByteArray()),
            state = state, candidates = listOf(tera), deadlineEpochMillis = Long.MAX_VALUE,
        ).copy(exactOwnTeam = BattleExactOwnTeamView(listOf(
            build(ACE, activeTera),
            build(SPARE, benchTera),
        )))
    }

    private fun build(id: UUID, tera: String) =
        BattleExactPokemonBuildView(id, "none", null, "serious", "N", EVS, EVS.mapValues { 31 }, tera, "probe")

    private fun rolesState() = state(listOf(
        pokemon(ACE, BattleSide.ALLY, 0, setOf("water")),
        pokemon(IDLE, BattleSide.ALLY, null, setOf("normal")),
        pokemon(FOE_A, BattleSide.OPPONENT, 0, setOf("fire")),
    ))

    private fun statusContext() = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("status-fit".toByteArray()),
        state = state(listOf(
            pokemon(ACE, BattleSide.ALLY, 0, setOf("normal"), stats = BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100)),
            pokemon(FOE_A, BattleSide.OPPONENT, 0, setOf("normal")),
        )),
        candidates = listOf(attack("sure", accuracy = 100.0)), deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun target(attack: Int = 100, special: Int = 100, speed: Int = 100, ability: String? = null) =
        pokemon(FOE_A, BattleSide.OPPONENT, 0, setOf("normal"), ability = ability,
            stats = publicExactStats(100, attack, 100, special, 100, speed))

    private fun fit(status: String, target: BattlePokemonStateView, context: BattleDecisionContext) =
        LocalStatusTargetFit.multiplier(status, target, context, 1.0)

    private fun attack(id: String, accuracy: Double) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = if (id == "sure") 0 else 1,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "psychic", damageCategory = BattleMoveDamageCategory.SPECIAL, power = 120.0,
            accuracy = accuracy, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        ),
    )

    private fun knockoutContext(candidates: List<BattleActionCandidate>) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("sure-knockout".toByteArray()),
        state = state(listOf(
            pokemon(ACE, BattleSide.ALLY, 0, setOf("normal"), stats = BattleCombatStatRangesView.exact(150, 100, 100, 150, 100, 100)),
            pokemon(FOE_A, BattleSide.OPPONENT, 0, setOf("normal"), hp = 0.2, stats = publicExactStats(100, 100, 100, 100, 75, 100)),
            pokemon(FOE_B, BattleSide.OPPONENT, null, setOf("normal"), stats = publicExactStats(100, 100, 100, 100, 75, 100)),
        )),
        candidates = candidates, deadlineEpochMillis = Long.MAX_VALUE,
        memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
    )

    private fun state(pokemon: List<BattlePokemonStateView>) = BattleStateView(
        battleId = BATTLE, format = BattleFormat.SINGLE, turn = 3, pokemon = pokemon,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } },
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        slot: Int?,
        types: Set<String>,
        hp: Double = 1.0,
        ability: String? = null,
        stats: BattleCombatStatRangesView? = null,
    ) = BattlePokemonStateView(
        id, side, slot, "cobblemon:probe", null, 50, hp, null, emptyMap(), emptySet(), ability, null, hp <= 0.0, types,
        stats, knownVolatileEffectIds = emptySet(),
    )

    private fun BattleStateView.withHp(id: UUID, hp: Double): BattleStateView {
        val changed = pokemon.map {
            if (it.battlePokemonId != id) it else BattlePokemonStateView(
                it.battlePokemonId, it.side, it.activeSlot, it.speciesId, it.formId, it.level, hp, it.statusId,
                it.statStages, it.knownMoveIds, it.knownAbilityId, it.knownHeldItemId, hp <= 0.0, it.knownTypeIds,
                it.combatStats, it.knownFormStates, knownVolatileEffectIds = emptySet(),
            )
        }
        return state(changed)
    }

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a01")
        val ACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a02")
        val SPARE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a03")
        val IDLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a04")
        val FOE_A: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a05")
        val FOE_B: UUID = UUID.fromString("00000000-0000-0000-0000-000000000a06")
        val GROUND = setOf("ground")
        val BUG = setOf("bug")
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
