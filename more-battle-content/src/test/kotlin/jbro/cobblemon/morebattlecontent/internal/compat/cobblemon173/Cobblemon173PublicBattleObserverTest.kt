package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleAbilityAvailability
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.internal.ai.PublicAbilityPossibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173PublicBattleObserverTest {
    @Test
    fun `public action constraints persist until cleared and disappear on switch`() {
        val first = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val second = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, first, "shadowball", emptyList()))
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.TAUNT,
                active = true,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.ENCORE,
                active = true,
                lockedMoveId = "shadowball",
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.TRAPPED,
                active = true,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.RECHARGE,
                active = true,
            ),
        )

        val constrained = observer.publicSnapshot().pokemon.single().actionConstraints
        assertTrue(constrained.taunted)
        assertEquals("shadowball", constrained.encoreMoveId)
        assertTrue(constrained.trapped)
        assertTrue(constrained.mustRecharge)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, second))

        val afterSwitch = observer.publicSnapshot().pokemon.associateBy { it.battlePokemonId }
        assertNull(afterSwitch.getValue(first.battlePokemonId).activeSlot)
        assertEquals(BattlePokemonActionConstraintView.empty(), afterSwitch.getValue(first.battlePokemonId).actionConstraints)
        assertEquals(BattlePokemonActionConstraintView.empty(), afterSwitch.getValue(second.battlePokemonId).actionConstraints)
    }

    @Test
    fun `public snapshots cannot reveal moves abilities or held items before an event`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0).copy(
            knownTypeIds = setOf("dragon", "ground"),
        )
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(turn = 0, pokemon = opponent))
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                turn = 1,
                pokemon = opponent.copy(speciesId = "cobblemon:zoroark", hpFraction = 0.5),
            ),
        )

        val known = observer.publicSnapshot().pokemon.single()
        assertTrue(known.knownMoveIds.isEmpty())
        assertNull(known.knownAbilityId)
        assertNull(known.knownHeldItemId)
        assertEquals(0.5, known.hpFraction)
        assertEquals(opponent.speciesId, known.speciesId)
        assertEquals(setOf("dragon", "ground"), known.knownTypeIds)
    }

    @Test
    fun `revealed resources and hp delta are accumulated from public events`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "thunderbolt", listOf(target)))
        val unrelatedHiddenState = opponent.copy(
            speciesId = "cobblemon:zoroark",
            hpFraction = 0.01,
            statusId = "brn",
        )
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, unrelatedHiddenState, "static"))
        observer.observe(Cobblemon173PublicObservation.HeldItemRevealed(1, unrelatedHiddenState, "choicespecs"))
        observer.observe(Cobblemon173PublicObservation.HpChanged(1, target.copy(hpFraction = 0.4)))

        val snapshot = observer.publicSnapshot()
        val knownOpponent = snapshot.pokemon.single { it.battlePokemonId == opponent.battlePokemonId }
        assertEquals(setOf("thunderbolt"), knownOpponent.knownMoveIds)
        assertEquals("static", knownOpponent.knownAbilityId)
        assertEquals("choicespecs", knownOpponent.knownHeldItemId)
        assertEquals(opponent.speciesId, knownOpponent.speciesId)
        assertEquals(1.0, knownOpponent.hpFraction)
        assertNull(knownOpponent.statusId)
        assertTrue(snapshot.events.zipWithNext().all { (before, after) -> before.sequence < after.sequence })
        assertEquals(
            listOf(BattleObservedEventKind.ACTION_ORDER, BattleObservedEventKind.MOVE_USED),
            snapshot.events.filter { it.kind in setOf(BattleObservedEventKind.ACTION_ORDER, BattleObservedEventKind.MOVE_USED) }
                .map { it.kind },
        )
        assertEquals(
            -0.6,
            snapshot.events.last { it.kind == BattleObservedEventKind.HP_CHANGED }.hpFractionDelta!!,
            0.000_001,
        )
    }

    @Test
    fun `direct target hp loss links to the preceding public action window`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(
            Cobblemon173PublicObservation.MoveUsed(
                turn = 1,
                actor = opponent,
                moveId = "quickattack",
                targets = listOf(target),
                baseMovePriority = 1,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                turn = 1,
                pokemon = target.copy(hpFraction = 0.75),
                allowPrecedingActionLink = true,
            ),
        )

        val events = observer.publicSnapshot().events
        val action = events.single { it.kind == BattleObservedEventKind.ACTION_ORDER }
        val damage = events.single { it.kind == BattleObservedEventKind.HP_CHANGED }
        assertEquals("quickattack", action.publicValueId)
        assertEquals(1, action.baseMovePriority)
        assertEquals(action.sequence, damage.precedingActionSequence)
        assertEquals(opponent.battlePokemonId, damage.precedingActionActorPokemonId)
        assertEquals("quickattack", damage.precedingActionMoveId)
        assertNull(damage.publicSourceEffectId)
    }

    @Test
    fun `public residual source and closed action window cannot be mislabeled as direct move damage`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "tackle", listOf(target), 0))
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                0,
                target.copy(hpFraction = 0.9),
                allowPrecedingActionLink = true,
                publicSourceEffectId = "brn",
            ),
        )
        observer.closeActionWindow()
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                1,
                target.copy(hpFraction = 0.8),
                allowPrecedingActionLink = true,
            ),
        )

        val damage = observer.publicSnapshot().events.filter { it.kind == BattleObservedEventKind.HP_CHANGED }
        assertEquals("brn", damage[0].publicSourceEffectId)
        assertNull(damage[0].precedingActionSequence)
        assertNull(damage[1].publicSourceEffectId)
        assertNull(damage[1].precedingActionSequence)
    }

    @Test
    fun `public move outcomes retain explicit participants and deduplicate the two miss forms`() {
        val source = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, source))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "rockblast", listOf(target), 0, missed = true))
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.MISSED),
                source,
                listOf(target),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.CRITICAL_HIT),
                targets = listOf(target),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT, hitCount = 3),
                targets = listOf(target),
            ),
        )

        val outcomes = observer.publicSnapshot().events.filter { it.kind == BattleObservedEventKind.MOVE_OUTCOME }
        assertEquals(3, outcomes.size)
        assertEquals(BattleMoveOutcomeKind.MISSED, outcomes[0].moveOutcome?.kind)
        assertEquals("rockblast", outcomes[0].moveOutcome?.moveId)
        assertEquals(source.battlePokemonId, outcomes[0].actorPokemonId)
        assertEquals(listOf(target.battlePokemonId), outcomes[0].targetPokemonIds)
        assertNull(outcomes[0].precedingActionSequence)
        assertEquals(BattleMoveOutcomeKind.CRITICAL_HIT, outcomes[1].moveOutcome?.kind)
        assertEquals(3, outcomes[2].moveOutcome?.hitCount)
    }

    @Test
    fun `substitute damage remains a public effect without changing pokemon hp`() {
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0).copy(hpFraction = 0.75)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(
                    BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
                    publicEffectId = "substitute",
                ),
                targets = listOf(target),
            ),
        )

        val snapshot = observer.publicSnapshot()
        assertEquals(0.75, snapshot.pokemon.single().hpFraction)
        assertTrue(snapshot.events.none { it.kind == BattleObservedEventKind.HP_CHANGED })
        val outcome = snapshot.events.single { it.kind == BattleObservedEventKind.MOVE_OUTCOME }
        assertEquals(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, outcome.moveOutcome?.kind)
        assertEquals("substitute", outcome.moveOutcome?.publicEffectId)
        assertNull(outcome.actorPokemonId)
        assertEquals(listOf(target.battlePokemonId), outcome.targetPokemonIds)
    }

    @Test
    fun `remaining opponent count uses configured roster size and unique public faints`() {
        val first = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.Fainted(2, first))
        observer.observe(Cobblemon173PublicObservation.Fainted(2, first))

        val snapshot = observer.publicSnapshot()
        assertEquals(2, snapshot.remainingOpponentPokemon)
        assertEquals(1, snapshot.pokemon.size)
        assertTrue(snapshot.pokemon.single().fainted)
    }

    @Test
    fun `field effects expose fair duration ranges and count down without weather upkeep resets`() {
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 4)

        observer.observe(
            Cobblemon173PublicObservation.WeatherChanged(
                0,
                "raindance",
                BattleIntegerRange(5, 8),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "electricterrain",
                FieldEffectScope.TERRAIN,
                true,
                BattleIntegerRange(5, 8),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "trickroom",
                FieldEffectScope.ROOM,
                true,
                BattleIntegerRange(5, 7),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "mudsport",
                FieldEffectScope.GLOBAL,
                true,
                BattleIntegerRange(5, 5),
            ),
        )
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(1, BattleSide.OPPONENT, "spikes", true))
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(1, BattleSide.OPPONENT, "spikes", true))

        val field = observer.publicSnapshot().field
        assertEquals("raindance", field.weather?.effectId)
        assertNull(field.weather?.remainingTurns)
        assertEquals(BattleIntegerRange(5, 8), field.weather?.remainingTurnsRange)
        assertEquals("electricterrain", field.terrain?.effectId)
        assertEquals(listOf("trickroom"), field.roomEffects.map { it.effectId })
        assertEquals(BattleIntegerRange(5, 7), field.roomEffects.single().remainingTurnsRange)
        assertEquals(5, field.globalEffects.single().remainingTurns)
        assertEquals(listOf("spikes"), field.sideConditions.getValue(BattleSide.OPPONENT).map { it.effectId })
        assertEquals(2, field.sideConditions.getValue(BattleSide.OPPONENT).single().stacks)

        observer.advanceTurn(2)
        observer.observe(
            Cobblemon173PublicObservation.WeatherChanged(
                2,
                "raindance",
                BattleIntegerRange(5, 8),
                upkeep = true,
            ),
        )
        val countedDown = observer.publicSnapshot().field
        assertEquals(BattleIntegerRange(4, 7), countedDown.weather?.remainingTurnsRange)
        assertEquals(BattleIntegerRange(4, 7), countedDown.terrain?.remainingTurnsRange)
        assertEquals(4, countedDown.globalEffects.single().remainingTurns)

        observer.observe(Cobblemon173PublicObservation.FieldEffectChanged(2, "trickroom", FieldEffectScope.ROOM, false))
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(2, BattleSide.OPPONENT, "spikes", false))
        val cleared = observer.publicSnapshot().field
        assertTrue(cleared.roomEffects.isEmpty())
        assertTrue(cleared.sideConditions.getValue(BattleSide.OPPONENT).isEmpty())
    }

    @Test
    fun `installed showdown duration knowledge separates exact ranged and indefinite effects`() {
        assertEquals(BattleIntegerRange(5, 8), Cobblemon173PublicEffectDurationKnowledge.weather("raindance"))
        assertNull(Cobblemon173PublicEffectDurationKnowledge.weather("primordialsea"))
        assertEquals(
            BattleIntegerRange(5, 8),
            Cobblemon173PublicEffectDurationKnowledge.field("psychicterrain", FieldEffectScope.TERRAIN),
        )
        assertEquals(
            BattleIntegerRange(5, 7),
            Cobblemon173PublicEffectDurationKnowledge.field("trickroom", FieldEffectScope.ROOM),
        )
        assertEquals(BattleIntegerRange(4, 6), Cobblemon173PublicEffectDurationKnowledge.side("tailwind"))
        assertEquals(BattleIntegerRange(5, 5), Cobblemon173PublicEffectDurationKnowledge.side("mist"))
        assertNull(Cobblemon173PublicEffectDurationKnowledge.side("stealthrock"))
    }

    @Test
    fun `assembler merges full ally state with public opponent state`() {
        val ally = ownPokemon()
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        val publicAlly = publicPokemon(BattleSide.ALLY, activeSlot = 0).copy(battlePokemonId = ally.battlePokemonId)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, publicAlly))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "tackle", listOf(publicAlly), 0))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, publicAlly, "protect", emptyList(), 0))

        val state = Cobblemon173BattleStateAssembler.assemble(
            battleId = UUID.randomUUID(),
            format = BattleFormat.SINGLE,
            turn = 1,
            ownPokemon = listOf(ally),
            publicSnapshot = observer.publicSnapshot(),
            inferenceKnowledge = { _, _ ->
                listOf(
                    PublicAbilityPossibility("roughskin", BattleAbilityAvailability.REGULAR),
                    PublicAbilityPossibility("sandveil", BattleAbilityAvailability.HIDDEN),
                )
            },
        )

        assertEquals(2, state.pokemon.size)
        assertEquals(1, state.remainingPokemonBySide.getValue(BattleSide.ALLY))
        assertEquals(3, state.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
        assertEquals(setOf("protect"), state.pokemon.single { it.side == BattleSide.ALLY }.knownMoveIds)
        assertEquals(setOf("tackle"), state.pokemon.single { it.side == BattleSide.OPPONENT }.knownMoveIds)
        val abilities = state.inferences.filter { it.categoryId == "ability" }
        assertEquals(setOf("roughskin", "sandveil"), abilities.mapNotNull { it.candidateId }.toSet())
        assertEquals(
            setOf(BattleAbilityAvailability.REGULAR, BattleAbilityAvailability.HIDDEN),
            abilities.mapNotNull { it.abilityAvailability }.toSet(),
        )
        assertTrue(abilities.all { it.confidence == BattleInferenceConfidence.POSSIBLE })
        val order = state.inferences.single { it.categoryId == "observed_action_order" }
        assertEquals("BEFORE_AT_SAME_BASE_PRIORITY", order.candidateId)
        assertEquals(ally.battlePokemonId, order.relatedPokemonId)
    }

    @Test
    fun `normalizes public Showdown ids and parses visible hp text`() {
        assertEquals("choicescarf", Cobblemon173ShowdownObservationAdapter.effectId("item: Choice Scarf"))
        assertEquals("sandstream", Cobblemon173ShowdownObservationAdapter.effectId("ability: Sand Stream"))
        assertEquals("uturn", Cobblemon173ShowdownObservationAdapter.effectId("U-turn"))
        assertEquals(0.5, Cobblemon173ShowdownObservationAdapter.parseHpFraction("100/200"))
        assertEquals(0.25, Cobblemon173ShowdownObservationAdapter.parseHpFraction("25/100 par"))
        assertEquals(0.0, Cobblemon173ShowdownObservationAdapter.parseHpFraction("0 fnt"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("75%"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("0/0"))
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TAUNT, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-start|p1a: Pikachu|move: Taunt"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.ENCORE, false),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-end|p1a: Pikachu|Encore"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.RECHARGE, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-mustrecharge|p1a: Pikachu"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-activate|p1a: Pikachu|move: Fire Spin|[of] p2a: Garchomp"),
            ),
        )
        assertEquals(4, Cobblemon173ShowdownObservationAdapter.publicTurn("999", currentBattleTurn = 4, previous = 3))
        assertEquals(3, Cobblemon173ShowdownObservationAdapter.publicTurn("broken", currentBattleTurn = 4, previous = 3))
        assertEquals(
            "brn",
            Cobblemon173ShowdownObservationAdapter.publicSourceEffectId(
                BattleMessage("|-damage|p1a: Pikachu|80/100|[from] brn"),
            ),
        )
        assertNull(
            Cobblemon173ShowdownObservationAdapter.publicSourceEffectId(
                BattleMessage("|-damage|p1a: Pikachu|80/100"),
            ),
        )

        val miss = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-miss|p1a: Pikachu|p2a: Garchomp"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.MISSED, miss.outcome.kind)
        assertEquals(0, miss.sourceArgument)
        assertEquals(listOf(1), miss.targetArguments)

        val block = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-block|p2a: Garchomp|move: Protect|move: Toxic|p1a: Pikachu"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.BLOCKED, block.outcome.kind)
        assertEquals("protect", block.outcome.publicEffectId)
        assertEquals("toxic", block.outcome.moveId)
        assertEquals(3, block.sourceArgument)
        assertEquals(listOf(0), block.targetArguments)

        val substitute = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|move: Substitute|[damage]"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, substitute.outcome.kind)
        assertEquals("substitute", substitute.outcome.publicEffectId)
        assertNull(substitute.outcome.moveId)
        assertNull(substitute.sourceArgument)
        assertEquals(listOf(0), substitute.targetArguments)

        val protection = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-singleturn|p1a: Pikachu|move: Protect"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.PROTECTION_STARTED, protection.outcome.kind)
        assertEquals("protect", protection.outcome.publicEffectId)
        assertNull(protection.outcome.moveId)
        assertNull(protection.sourceArgument)
        assertEquals(listOf(0), protection.targetArguments)
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|ability: Sturdy"),
            ),
        )
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|move: Substitute"),
            ),
        )
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-singleturn|p1a: Pikachu|move: Endure"),
            ),
        )

        val hitCount = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-hitcount|p2a: Garchomp|4"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.HIT_COUNT, hitCount.outcome.kind)
        assertEquals(4, hitCount.outcome.hitCount)
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-hitcount|p2a: Garchomp|broken"),
            ),
        )
        val outcomeKinds = mapOf(
            "|-fail|p2a: Garchomp|move: Toxic" to BattleMoveOutcomeKind.FAILED,
            "|-notarget|p1a: Pikachu" to BattleMoveOutcomeKind.NO_TARGET,
            "|cant|p1a: Pikachu|par|move: Thunderbolt" to BattleMoveOutcomeKind.CANNOT_ACT,
            "|-crit|p2a: Garchomp" to BattleMoveOutcomeKind.CRITICAL_HIT,
            "|-supereffective|p2a: Garchomp" to BattleMoveOutcomeKind.SUPER_EFFECTIVE,
            "|-resisted|p2a: Garchomp" to BattleMoveOutcomeKind.RESISTED,
            "|-immune|p2a: Garchomp" to BattleMoveOutcomeKind.IMMUNE,
        )
        outcomeKinds.forEach { (raw, expected) ->
            assertEquals(
                expected,
                requireNotNull(
                    Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(BattleMessage(raw)),
                ).outcome.kind,
            )
        }

        val hiddenResolverResult = publicPokemon(BattleSide.OPPONENT, activeSlot = null).copy(
            speciesId = "cobblemon:zoroark",
            formId = "hisui",
            level = 100,
        )
        val publicSwitch = Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(
            hiddenResolverResult,
            "Pikachu, L50, M",
        )
        assertEquals("showdown:pikachu", publicSwitch.speciesId)
        assertNull(publicSwitch.formId)
        assertEquals(50, publicSwitch.level)
    }

    private fun publicPokemon(side: BattleSide, activeSlot: Int?) = Cobblemon173PublicPokemonSnapshot(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = activeSlot,
        speciesId = if (side == BattleSide.OPPONENT) "cobblemon:garchomp" else "cobblemon:rotom",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        fainted = false,
    )

    private fun ownPokemon() = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = BattleSide.ALLY,
        activeSlot = 0,
        speciesId = "cobblemon:metagross",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = setOf("protect"),
        knownAbilityId = "clearbody",
        knownHeldItemId = "leftovers",
        fainted = false,
    )
}
