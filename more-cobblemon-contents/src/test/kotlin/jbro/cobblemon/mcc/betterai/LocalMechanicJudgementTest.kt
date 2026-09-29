package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector
import jbro.cobblemon.mcc.betterai.search.LocalResponseValue
import jbro.cobblemon.mcc.betterai.search.LocalSearchResponseObjective
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalMechanicJudgementTest {
    // ---- Tera and Mega persist past the turn they are used ----

    @Test
    fun `terastallization leaves the new defensive type on the Pokemon for later turns`() {
        val state = state(listOf(ally(ACTIVE, 0, setOf("grass")), foe(FOE_A, 0, setOf("fire"))))
        val tera = move(ACTIVE_MOVE, mechanic = BattleMechanicCandidate("cobblemon:terastallize", null, null,
            transformedActorTypeIds = setOf("water")))

        val after = LocalMechanicActivationProjector.beforeMoves(state, BattleSide.ALLY, tera)
        val actor = after.pokemon.single { it.battlePokemonId == ACTIVE }

        assertEquals(setOf("water"), actor.knownTypeIds)
        assertEquals("water", actor.knownTeraTypeId)
        assertEquals(setOf("grass"), actor.knownBaseStabTypeIds, "Tera keeps the original STAB types")
        assertEquals(after, LocalMechanicActivationProjector.beforeMoves(after, BattleSide.ALLY, tera),
            "A Pokemon can terastallize only once")
    }

    @Test
    fun `mega evolution switches to the published mega form and keeps it`() {
        val megaForm = BattlePokemonFormStateView("mega", setOf("water", "dark"), STATS)
        val base = ally(ACTIVE, 0, setOf("water", "flying")).let {
            BattlePokemonStateView(it.battlePokemonId, it.side, it.activeSlot, "cobblemon:gyarados", null, 50, 1.0,
                null, emptyMap(), emptySet(), "intimidate", "gyaradosite", false, it.knownTypeIds, STATS,
                mapOf("mega" to megaForm), knownVolatileEffectIds = emptySet())
        }
        val state = state(listOf(base, foe(FOE_A, 0, setOf("electric"))))
        val mega = move(ACTIVE_MOVE, mechanic = BattleMechanicCandidate("cobblemon:mega", null, null))

        val actor = LocalMechanicActivationProjector.beforeMoves(state, BattleSide.ALLY, mega)
            .pokemon.single { it.battlePokemonId == ACTIVE }

        assertEquals("mega", actor.formId)
        assertEquals(setOf("water", "dark"), actor.knownTypeIds)
    }

    // ---- Threat weights ----

    @Test
    fun `introductory sees every opponent as equally dangerous`() {
        assertTrue(LocalOpponentThreat.weights(fireWeakContext(), BattleTrainerTier.INTRODUCTORY).isEmpty())
    }

    @Test
    fun `standard weighs a fire attacker higher against a fire weak party within its band`() {
        val weights = LocalOpponentThreat.weights(fireWeakContext(), BattleTrainerTier.STANDARD)
        val band = LocalOpponentThreat.band(BattleTrainerTier.STANDARD)

        assertTrue(weights.getValue(FOE_A) > weights.getValue(FOE_B), "$weights")
        assertTrue(weights.values.all { it in band.minimum..band.maximum }, "$weights")
        assertEquals(1.0, weights.values.average(), 0.15)
    }

    @Test
    fun `knocking out the dangerous opponent raises the AI-side material adjustment`() {
        val weights = mapOf(FOE_A to 1.3, FOE_B to 0.8)
        val before = fireWeakContext().state
        val afterKo = before.copyWithPokemon(FOE_A) { it.withHp(0.0) }
        val afterMinorKo = before.copyWithPokemon(FOE_B) { it.withHp(0.0) }

        val threatKo = LocalOpponentThreat.materialAdjustment(afterKo, weights) -
            LocalOpponentThreat.materialAdjustment(before, weights)
        val minorKo = LocalOpponentThreat.materialAdjustment(afterMinorKo, weights) -
            LocalOpponentThreat.materialAdjustment(before, weights)

        assertTrue(threatKo > 0.0 && minorKo < 0.0, "threat=$threatKo minor=$minorKo")
    }

    @Test
    fun `the threat delta never changes which opponent response the search treats as worst`() {
        val low = LocalResponseValue(value = 0.0, ownExecutionProbability = 1.0, ownRemainingHpFraction = 1.0, threatDelta = 5.0)
        val high = LocalResponseValue(value = 1.0, ownExecutionProbability = 1.0, ownRemainingHpFraction = 1.0, threatDelta = -5.0)
        val swapped = listOf(low.copy(threatDelta = -5.0), high.copy(threatDelta = 5.0))

        val robust = LocalSearchResponseObjective.robust(listOf(low, high), BattleTrainerTier.BOSS)
        val robustSwapped = LocalSearchResponseObjective.robust(swapped, BattleTrainerTier.BOSS)

        assertEquals(robust.value, robustSwapped.value, 1e-12, "Response weights must come from the plain value")
        assertTrue(robust.threatDelta > 0.0 && robustSwapped.threatDelta < 0.0)
    }

    // ---- Mechanic option value ----

    @Test
    fun `mega evolution and a last pokemon cost nothing to use`() {
        val context = fireWeakContext()
        val mega = move(ACTIVE_MOVE, mechanic = BattleMechanicCandidate("cobblemon:mega", null, null))
        assertEquals(0.0, LocalMechanicOptionValue.cost(mega, context, BattleTrainerProfile.boss()))

        val alone = context.copy(state = context.state.copyWithPokemon(BENCH) { it.withHp(0.0) })
        assertEquals(0.0, LocalMechanicOptionValue.cost(teraMove(), alone, BattleTrainerProfile.boss()))
    }

    @Test
    fun `tera costs more when a benched ally could use it to escape a fire weakness`() {
        val boss = LocalMechanicOptionValue.cost(teraMove(), fireWeakContext(), BattleTrainerProfile.boss())
        val standard = LocalMechanicOptionValue.cost(teraMove(), fireWeakContext(), BattleTrainerProfile.balanced(2))
        val introductory = LocalMechanicOptionValue.cost(teraMove(), fireWeakContext(), BattleTrainerProfile.balanced(0))

        assertTrue(boss > 0.0, "boss=$boss")
        assertTrue(standard < boss, "standard=$standard boss=$boss")
        assertEquals(0.0, introductory)
    }

    @Test
    fun `a cautious trainer hoards tera more than an aggressive one`() {
        val boss = BattleTrainerProfile.boss()
        val cautious = boss.copy(personality = boss.personality.copy(caution = 1.0, aggression = 0.0))
        val aggressive = boss.copy(personality = boss.personality.copy(caution = 0.0, aggression = 1.0))

        assertTrue(LocalMechanicOptionValue.cost(teraMove(), fireWeakContext(), cautious) >
            LocalMechanicOptionValue.cost(teraMove(), fireWeakContext(), aggressive))
    }

    // ---- fixtures ----

    private fun fireWeakContext(): BattleDecisionContext {
        val state = state(listOf(
            ally(ACTIVE, 0, setOf("grass")),
            ally(BENCH, null, setOf("bug")),
            foe(FOE_A, 0, setOf("fire")),
            foe(FOE_B, null, setOf("water")),
        ))
        val exact = BattleExactOwnTeamView(listOf(
            BattleExactPokemonBuildView(ACTIVE, "overgrow", null, "serious", "N", EVS, IVS, "grass", "venusaur"),
            BattleExactPokemonBuildView(BENCH, "swarm", null, "serious", "N", EVS, IVS, "water", "scizor"),
        ))
        return BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("mechanic-judgement".toByteArray()),
            state = state,
            candidates = listOf(teraMove()),
            deadlineEpochMillis = Long.MAX_VALUE,
        ).copy(exactOwnTeam = exact)
    }

    private fun teraMove() = move(ACTIVE_MOVE, mechanic = BattleMechanicCandidate("cobblemon:terastallize", null, null,
        transformedActorTypeIds = setOf("grass")))

    private fun move(id: String, mechanic: BattleMechanicCandidate? = null) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "tackle",
        mechanic = mechanic,
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 10),
    )

    private fun state(pokemon: List<BattlePokemonStateView>) = BattleStateView(
        battleId = BATTLE, format = BattleFormat.SINGLE, turn = 3, pokemon = pokemon,
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to pokemon.count { it.side == BattleSide.ALLY },
            BattleSide.OPPONENT to pokemon.count { it.side == BattleSide.OPPONENT },
        ),
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun ally(id: UUID, slot: Int?, types: Set<String>) = pokemon(id, BattleSide.ALLY, slot, types)
    private fun foe(id: UUID, slot: Int?, types: Set<String>) = pokemon(id, BattleSide.OPPONENT, slot, types)

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, types: Set<String>) = BattlePokemonStateView(
        id, side, slot, "cobblemon:test", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false, types,
        knownVolatileEffectIds = emptySet(),
    )

    private fun BattleStateView.copyWithPokemon(
        id: UUID,
        change: (BattlePokemonStateView) -> BattlePokemonStateView,
    ): BattleStateView {
        val changed = pokemon.map { if (it.battlePokemonId == id) change(it) else it }
        return BattleStateView(battleId, format, turn, changed, field,
            BattleSide.entries.associateWith { side -> changed.count { it.side == side && !it.fainted } },
            observedEvents, inferences)
    }

    private fun BattlePokemonStateView.withHp(hp: Double) = BattlePokemonStateView(
        battlePokemonId, side, activeSlot, speciesId, formId, level, hp, statusId, statStages, knownMoveIds,
        knownAbilityId, knownHeldItemId, hp <= 0.0, knownTypeIds, combatStats, knownFormStates,
        knownVolatileEffectIds = emptySet(),
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000901")
        val ACTIVE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000902")
        val BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000000903")
        val FOE_A: UUID = UUID.fromString("00000000-0000-0000-0000-000000000904")
        val FOE_B: UUID = UUID.fromString("00000000-0000-0000-0000-000000000905")
        const val ACTIVE_MOVE = "move-active"
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val IVS = EVS.mapValues { 31 }
        val STATS = BattleCombatStatRangesView(
            BattleIntegerRange(150, 150), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleCombatStatKnowledge.EXACT_OWN,
        )
    }
}
