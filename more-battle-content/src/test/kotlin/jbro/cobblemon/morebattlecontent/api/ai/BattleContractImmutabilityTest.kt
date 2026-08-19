package jbro.cobblemon.morebattlecontent.api.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleContractImmutabilityTest {
    @Test
    fun `state snapshot detaches all caller owned collections`() {
        val pokemonId = UUID.randomUUID()
        val statStages = linkedMapOf("attack" to 1)
        val knownMoves = linkedSetOf("tackle")
        val knownTypes = linkedSetOf("normal")
        val pokemon = BattlePokemonStateView(
            battlePokemonId = pokemonId,
            side = BattleSide.ALLY,
            activeSlot = 0,
            speciesId = "test_species",
            formId = null,
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = statStages,
            knownMoveIds = knownMoves,
            knownAbilityId = "test_ability",
            knownHeldItemId = null,
            fainted = false,
            knownTypeIds = knownTypes,
        )
        val roomEffects = mutableListOf(BattleTimedEffectView("trick_room", 3))
        val allyConditions = mutableListOf(BattleTimedEffectView("reflect", 2))
        val sideConditions = linkedMapOf(
            BattleSide.ALLY to allyConditions,
            BattleSide.OPPONENT to emptyList(),
        )
        val field = BattleFieldStateView(null, null, roomEffects, emptyList(), sideConditions)
        val pokemonList = mutableListOf(pokemon)
        val remaining = linkedMapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1)
        val events = mutableListOf<BattleObservedEventView>()
        val state = BattleStateView(
            UUID.randomUUID(),
            BattleFormat.SINGLE,
            1,
            pokemonList,
            field,
            remaining,
            events,
            emptyList(),
        )

        statStages["attack"] = 6
        knownMoves += "hidden_move"
        knownTypes += "ghost"
        roomEffects.clear()
        allyConditions.clear()
        sideConditions.clear()
        pokemonList.clear()
        remaining[BattleSide.ALLY] = 0
        events += BattleObservedEventView(1, 1, BattleObservedEventKind.SWITCHED, pokemonId)

        assertEquals(mapOf("attack" to 1), pokemon.statStages)
        assertEquals(setOf("tackle"), pokemon.knownMoveIds)
        assertEquals(setOf("normal"), pokemon.knownTypeIds)
        assertEquals("trick_room", field.roomEffects.single().effectId)
        assertEquals("reflect", field.sideConditions.getValue(BattleSide.ALLY).single().effectId)
        assertEquals(1, state.pokemon.size)
        assertEquals(1, state.remainingPokemonBySide.getValue(BattleSide.ALLY))
        assertTrue(state.observedEvents.isEmpty())
    }

    @Test
    fun `action decision and provider detach caller owned collections`() {
        val targets = mutableListOf(BattleTargetSlot(BattleSide.OPPONENT, 0))
        val tags = linkedSetOf("safe")
        val candidate = BattleActionCandidate(
            actionId = "move:0",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            targets = targets,
            tags = tags,
        )
        val candidates = mutableListOf(candidate)
        val decisionContext = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = emptyState(),
            candidates = candidates,
            deadlineEpochMillis = 1_000,
        )
        val capabilities = linkedSetOf(BrainCapability.SINGLE)
        val provider = BattleBrainProvider(
            BrainId("test:immutable"),
            capabilities,
            BattleBrainFactory { error("not created") },
        )

        targets.clear()
        tags.clear()
        candidates.clear()
        capabilities += BrainCapability.DOUBLE

        assertEquals(1, candidate.targets.size)
        assertEquals(setOf("safe"), candidate.tags)
        assertEquals(1, decisionContext.candidates.size)
        assertEquals(setOf(BrainCapability.SINGLE), provider.capabilities)
    }

    @Test
    fun `composite actions preserve detached public component details`() {
        val move = BattleActionCandidate(
            actionId = "move:0",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveDetails = BattleMoveCandidateView(
                typeId = "fire",
                damageCategory = BattleMoveDamageCategory.SPECIAL,
                power = 90.0,
                accuracy = 100.0,
                priority = 0,
                currentPp = 10,
            ),
        )
        val components = mutableListOf(move)
        val composite = BattleActionCandidate(
            actionId = "turn:move:0",
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf(move.actionId),
            componentActions = components,
        )

        components.clear()

        assertEquals(1, composite.componentActions.size)
        assertEquals(90.0, composite.componentActions.single().moveDetails?.power)
    }

    @Test
    fun `strategy brief detaches structured objectives and keeps human and ai explanations separate`() {
        val objectives = linkedSetOf(BattleStrategyObjective.FIELD_CONTROL, BattleStrategyObjective.SPREAD_PRESSURE)
        val roles = linkedSetOf(BattleTeamRole.ACE, BattleTeamRole.FIELD_SUPPORT)
        val preferredMoves = linkedSetOf("cobblemon:rain_dance")
        val members = mutableListOf(
            BattleTeamMemberPlan(
                speciesId = "cobblemon:pelipper",
                roles = roles,
                tacticalSummary = "Set rain, then preserve enough HP to enable the ace later.",
                preferredMoveIds = preferredMoves,
                leadPriority = 100,
                preservationPriority = 80,
            ),
        )
        val brief = BattleStrategyBrief(
            strategyId = "mbc:rain_pressure",
            displayNameKey = "strategy.mbc.rain_pressure.name",
            descriptionKey = "strategy.mbc.rain_pressure.description",
            aiSummary = "Establish rain, preserve the setter, then pressure both opposing slots.",
            objectives = objectives,
            members = members,
        )

        objectives.clear()
        roles.clear()
        preferredMoves.clear()
        members.clear()

        assertEquals(setOf(BattleStrategyObjective.FIELD_CONTROL, BattleStrategyObjective.SPREAD_PRESSURE), brief.objectives)
        assertEquals(1, brief.members.size)
        assertEquals(setOf(BattleTeamRole.ACE, BattleTeamRole.FIELD_SUPPORT), brief.members.single().roles)
        assertEquals(setOf("cobblemon:rain_dance"), brief.members.single().preferredMoveIds)
        assertEquals(100, brief.members.single().leadPriority)
        assertEquals(80, brief.members.single().preservationPriority)
        assertEquals(brief, BattleBrainOpenContext(UUID.randomUUID(), BattleFormat.DOUBLE, strategy = brief).strategy)
    }

    @Test
    fun `move effects copy effect and stat stage collections`() {
        val stages = linkedMapOf("attack" to 1)
        val acceptedValues = linkedSetOf("snow")
        val mechanicFlags = linkedSetOf("protect")
        val requirements = mutableListOf(
            BattleMoveRequirementView(
                kind = BattleMoveRequirementKind.WEATHER_ANY_OF,
                acceptedValueIds = acceptedValues,
            ),
        )
        val effects = mutableListOf(
            BattleMoveEffectView(
                kind = BattleMoveEffectKind.STAT_STAGE,
                target = BattleMoveEffectTarget.USER,
                probability = 1.0,
                statStages = stages,
            ),
        )
        val view = BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = effects,
            scriptedBehavior = false,
            requirements = requirements,
            mechanicFlags = mechanicFlags,
        )

        stages.clear()
        acceptedValues.clear()
        requirements.clear()
        mechanicFlags.clear()
        effects.clear()

        assertEquals(mapOf("attack" to 1), view.effects.single().statStages)
        assertEquals(1, view.effects.size)
        assertEquals(setOf("snow"), view.requirements.single().acceptedValueIds)
        assertEquals(1, view.requirements.size)
        assertEquals(setOf("protect"), view.mechanicFlags)
    }

    private fun emptyState() = BattleStateView(
        battleId = UUID.randomUUID(),
        format = BattleFormat.SINGLE,
        turn = 0,
        pokemon = emptyList(),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )
}
