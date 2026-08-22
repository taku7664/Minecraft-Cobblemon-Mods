package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalLookaheadEvaluationTest {
    @Test
    fun `snapshot action constraints filter the first recursive decision boundary`() {
        val attack = move("attack", power = 80.0)
        val status = move("recover", category = BattleMoveDamageCategory.STATUS, power = 0.0)
        val bench = pokemon(
            UUID.fromString("00000000-0000-0000-0000-000000000213"),
            BattleSide.ALLY,
            speed = 90,
            activeSlot = null,
        )
        val constrained = state(bench = bench, allyActionConstraints = BattlePokemonActionConstraintView(
            taunted = true,
            encoreMoveId = "cobblemon:attack",
            trapped = true,
        ))

        val actions = PublicFutureActionFactory.actions(
            constrained,
            BattleSide.ALLY,
            catalog(allyMoves = listOf(attack, status)),
        )

        assertEquals(listOf("cobblemon:attack"), actions.mapNotNull { it.moveId })
        assertTrue(actions.none { it.kind == BattleActionKind.SWITCH })

        val recharging = state(allyActionConstraints = BattlePokemonActionConstraintView(mustRecharge = true))
        val forced = PublicFutureActionFactory.actions(recharging, BattleSide.ALLY, catalog(allyMoves = listOf(attack, status)))
        assertEquals(1, forced.size)
        assertEquals(BattleActionKind.WAIT, forced.single().kind)
        assertTrue("forced_recharge" in forced.single().tags)
    }

    @Test
    fun `snapshot constraints transfer once into bounded recursive history`() {
        val initial = state(allyActionConstraints = BattlePokemonActionConstraintView(
            taunted = true,
            encoreMoveId = "cobblemon:attack",
            trapped = true,
            mustRecharge = true,
        ))

        val history = RecursiveSnapshotActionConstraints.seed(initial)
        val cleared = RecursiveSnapshotActionConstraints.clearFromProjectedState(initial)

        assertTrue(ALLY_ID in history.rechargingPokemonIds)
        assertTrue(history.tauntTurnsByPokemon.getValue(ALLY_ID) in 1..3)
        assertEquals("cobblemon:attack", history.encoreByPokemon.getValue(ALLY_ID).moveId)
        assertTrue(ALLY_ID in history.trappedByPokemon)
        assertEquals(BattlePokemonActionConstraintView.empty(), cleared.pokemon.single {
            it.battlePokemonId == ALLY_ID
        }.actionConstraints)
    }

    @Test
    fun `inaccurate knockout branches into miss and faint instead of average hp`() {
        val initial = state()
        val inaccurateKnockout = move(
            id = "inaccurate_knockout",
            power = 1_000.0,
            accuracy = 50.0,
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            inaccurateKnockout,
            wait("opponent_wait"),
            context(initial, listOf(inaccurateKnockout)),
        )

        assertEquals(2, outcomes.size)
        val targetStates = outcomes.associateBy {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == OPPONENT_ID }.hpFraction
        }
        assertEquals(0.50, requireNotNull(targetStates[1.0]).probability, 1e-9)
        assertEquals(0.50, requireNotNull(targetStates[0.0]).probability, 1e-9)
    }

    @Test
    fun `thirty percent stat drop branches into discrete outcomes`() {
        val initial = state()
        val moonblast = move(
            id = "moonblast",
            category = BattleMoveDamageCategory.SPECIAL,
            power = 95.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 0.30,
                    statStages = mapOf("special_attack" to -1),
                ),
            ),
        )

        val outcomes = PublicSingleTurnProjector.project(initial, moonblast, wait("opponent_wait"), context(initial, listOf(moonblast)))

        val dropped = outcomes.filter { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["special_attack"] == -1
        }
        val unchanged = outcomes.filter { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["special_attack"] == null
        }
        assertEquals(0.30, dropped.sumOf { it.probability }, 1e-9)
        assertEquals(0.70, unchanged.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `secondary effect probability remains conditional on a successful hit`() {
        val initial = state()
        val inaccurateMoonblast = move(
            id = "inaccurate_moonblast",
            category = BattleMoveDamageCategory.SPECIAL,
            power = 95.0,
            accuracy = 50.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 0.30,
                    statStages = mapOf("special_attack" to -1),
                ),
            ),
        )

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            inaccurateMoonblast,
            wait("opponent_wait"),
            context(initial, listOf(inaccurateMoonblast)),
        )

        val totalDropProbability = outcomes.filter { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }
                .statStages["special_attack"] == -1
        }.sumOf { it.probability }
        assertEquals(0.15, totalDropProbability, 1e-9)
    }

    @Test
    fun `protect prevents a later targeted hit in the projected turn`() {
        val initial = state(allySpeed = 80, opponentSpeed = 120)
        val protect = move(
            id = "protect",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            priority = 4,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.PROTECT_USER,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                ),
            ),
        )
        val opponentHit = move("opponent_hit", side = BattleSide.OPPONENT, power = 100.0)

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            protect,
            opponentHit,
            context(initial, listOf(protect)),
        )

        assertTrue(outcomes.all { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction == 1.0
        })
    }

    @Test
    fun `kings shield lowers only a blocked contact attackers attack`() {
        val initial = state(allySpeed = 80, opponentSpeed = 120)
        val kingsShield = move(
            id = "kings_shield",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            priority = 4,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.PROTECT_USER,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                ),
            ),
        )
        val contact = move(
            "contact_hit",
            side = BattleSide.OPPONENT,
            power = 100.0,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = false,
                mechanicFlags = setOf("contact"),
            ),
        )
        val nonContact = move("non_contact_hit", side = BattleSide.OPPONENT, power = 100.0)

        val contactOutcome = PublicSingleTurnProjector.project(
            initial,
            kingsShield,
            contact,
            context(initial, listOf(kingsShield)),
        ).single()
        val nonContactOutcome = PublicSingleTurnProjector.project(
            initial,
            kingsShield,
            nonContact,
            context(initial, listOf(kingsShield)),
        ).single()

        assertEquals(
            -1,
            contactOutcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["attack"],
        )
        assertEquals(
            null,
            nonContactOutcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["attack"],
        )
    }

    @Test
    fun `stance change uses known blade stats before an aegislash attack`() {
        val bladeStats = BattleCombatStatRangesView.exact(200, 300, 80, 300, 80, 100)
        val shieldStats = BattleCombatStatRangesView.exact(200, 80, 300, 80, 300, 100)
        val aegislash = pokemon(
            ALLY_ID,
            BattleSide.ALLY,
            speed = 100,
            speciesId = "cobblemon:aegislash",
            formId = "Normal",
            knownAbility = "cobblemon:stancechange",
            combatStats = shieldStats,
            knownFormStates = mapOf(
                "Normal" to BattlePokemonFormStateView("Normal", setOf("steel", "ghost"), shieldStats),
                "Blade" to BattlePokemonFormStateView("Blade", setOf("steel", "ghost"), bladeStats),
            ),
        )
        val initial = BattleStateView(
            battleId = BATTLE_ID,
            format = BattleFormat.SINGLE,
            turn = 1,
            pokemon = listOf(aegislash, pokemon(OPPONENT_ID, BattleSide.OPPONENT, speed = 80)),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val attack = move("iron_head", power = 40.0)

        val outcome = PublicSingleTurnProjector.project(
            initial,
            attack,
            wait("opponent_wait"),
            context(initial, listOf(attack)),
        ).first()
        val transformed = outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }

        assertEquals("Blade", transformed.formId)
        assertEquals(bladeStats, transformed.combatStats)
    }

    @Test
    fun `known prankster gives a status move one extra priority`() {
        val initial = state(allySpeed = 80, opponentSpeed = 120, allyAbility = "cobblemon:prankster")
        val statusMove = move(
            id = "prankster_status",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
        )
        val opponentHit = move("opponent_hit", side = BattleSide.OPPONENT, power = 40.0)

        val orders = PublicSingleTurnProjector.project(
            initial,
            statusMove,
            opponentHit,
            context(initial, listOf(statusMove)),
        ).map { it.order }.distinct()

        assertEquals(listOf(listOf(BattleSide.ALLY, BattleSide.OPPONENT)), orders)
    }

    @Test
    fun `known speed boost raises the active speed stage after the turn`() {
        val initial = state(allyAbility = "cobblemon:speed_boost")
        val ownWait = wait("ally_wait")

        val outcome = PublicSingleTurnProjector.project(
            initial,
            ownWait,
            wait("opponent_wait"),
            context(initial, listOf(ownWait)),
        ).single()

        assertEquals(1, outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages["speed"])
    }

    @Test
    fun `paralysis changes the projected speed order`() {
        val initial = state(allySpeed = 120, opponentSpeed = 100, allyStatus = "cobblemon:paralysis")

        assertEquals(LocalPublicSpeedRelation.OPPONENT_FIRST, LocalLookaheadStateEvaluator.speedRelation(initial))
    }

    @Test
    fun `known regenerator heals the outgoing pokemon during switch projection`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000214")
        val initial = state(
            allyHp = 0.20,
            allyAbility = "cobblemon:regenerator",
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
        )
        val switch = BattleActionCandidate(
            actionId = "regenerator_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )

        val projected = LocalSwitchStateProjector.project(initial, BattleSide.ALLY, switch)

        assertEquals(0.20 + 1.0 / 3.0, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `known intimidate lowers the opposing active attack on entry`() {
        val intimidatorId = UUID.fromString("00000000-0000-0000-0000-000000000220")
        val initial = state(
            opponentBench = pokemon(
                intimidatorId,
                BattleSide.OPPONENT,
                speed = 90,
                activeSlot = null,
                knownAbility = "cobblemon:intimidate",
            ),
        )
        val switch = BattleActionCandidate(
            actionId = "intimidate_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = intimidatorId,
        )

        val projected = LocalSwitchStateProjector.project(initial, BattleSide.OPPONENT, switch)

        assertEquals(-1, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages["attack"])
    }

    @Test
    fun `clear body blocks known intimidate on entry`() {
        val intimidatorId = UUID.fromString("00000000-0000-0000-0000-000000000221")
        val initial = state(
            allyAbility = "cobblemon:clear_body",
            opponentBench = pokemon(
                intimidatorId,
                BattleSide.OPPONENT,
                speed = 90,
                activeSlot = null,
                knownAbility = "cobblemon:intimidate",
            ),
        )
        val switch = BattleActionCandidate(
            actionId = "intimidate_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = intimidatorId,
        )

        val projected = LocalSwitchStateProjector.project(initial, BattleSide.OPPONENT, switch)

        assertEquals(null, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages["attack"])
    }

    @Test
    fun `defiant converts a known intimidate drop into net plus one attack`() {
        val intimidatorId = UUID.fromString("00000000-0000-0000-0000-000000000222")
        val initial = state(
            allyAbility = "cobblemon:defiant",
            opponentBench = pokemon(
                intimidatorId,
                BattleSide.OPPONENT,
                speed = 90,
                activeSlot = null,
                knownAbility = "cobblemon:intimidate",
            ),
        )
        val switch = BattleActionCandidate(
            actionId = "intimidate_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = intimidatorId,
        )

        val projected = LocalSwitchStateProjector.project(initial, BattleSide.OPPONENT, switch)

        assertEquals(1, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages["attack"])
    }

    @Test
    fun `mirror armor reflects a known intimidate drop onto the entrant`() {
        val intimidatorId = UUID.fromString("00000000-0000-0000-0000-000000000223")
        val initial = state(
            allyAbility = "cobblemon:mirror_armor",
            opponentBench = pokemon(
                intimidatorId,
                BattleSide.OPPONENT,
                speed = 90,
                activeSlot = null,
                knownAbility = "cobblemon:intimidate",
            ),
        )
        val switch = BattleActionCandidate(
            actionId = "intimidate_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = intimidatorId,
        )

        val projected = LocalSwitchStateProjector.project(initial, BattleSide.OPPONENT, switch)

        assertEquals(null, projected.pokemon.single { it.battlePokemonId == ALLY_ID }.statStages["attack"])
        assertEquals(-1, projected.pokemon.single { it.battlePokemonId == intimidatorId }.statStages["attack"])
    }

    @Test
    fun `future action generation excludes moves with no pp`() {
        val initial = state()
        val depleted = move("depleted_move", currentPp = 0)
        val usable = move("usable_move", currentPp = 1)
        val publicCatalog = catalog(allyMoves = listOf(depleted, usable))

        val actions = PublicFutureActionFactory.actions(initial, BattleSide.ALLY, publicCatalog)

        assertEquals(listOf("cobblemon:usable_move"), actions.mapNotNull { it.moveId })
    }

    @Test
    fun `future action generation subtracts pp spent inside the recursive line`() {
        val initial = state()
        val lastPp = move("last_pp", currentPp = 1)
        val reusable = move("reusable", currentPp = 2)
        val publicCatalog = catalog(allyMoves = listOf(lastPp, reusable))
        val history = RecursiveActionHistory(
            moveUses = mapOf(
                RecursiveMoveUseKey(ALLY_ID, "cobblemon:last_pp") to 1,
                RecursiveMoveUseKey(ALLY_ID, "cobblemon:reusable") to 1,
            ),
        )

        val actions = PublicFutureActionFactory.actions(initial, BattleSide.ALLY, publicCatalog, history)

        assertEquals(listOf("cobblemon:reusable"), actions.mapNotNull { it.moveId })
    }

    @Test
    fun `first entry moves disappear after acting and return after reentry`() {
        val initial = state()
        val firstImpression = move("firstimpression", priority = 2)
        val steady = move("steady")
        val publicCatalog = catalog(allyMoves = listOf(firstImpression, steady))

        val afterActing = PublicFutureActionFactory.actions(
            initial,
            BattleSide.ALLY,
            publicCatalog,
            RecursiveActionHistory(actedSinceEntryPokemonIds = setOf(ALLY_ID)),
        )
        val afterReentry = PublicFutureActionFactory.actions(
            initial,
            BattleSide.ALLY,
            publicCatalog,
            RecursiveActionHistory(),
        )

        assertEquals(listOf("cobblemon:steady"), afterActing.mapNotNull { it.moveId })
        assertEquals(
            listOf("cobblemon:firstimpression", "cobblemon:steady"),
            afterReentry.mapNotNull { it.moveId },
        )
    }

    @Test
    fun `pure recovery at full hp is mechanically inert before weighted selection`() {
        val initial = state(allyHp = 1.0)
        val recovery = move(
            "recover_at_full_hp",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.HEAL_FRACTION,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    fractionRange = BattleFractionRange(0.5, 0.5),
                ),
            ),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(context(initial, listOf(recovery)))

        val outcome = LocalBattleActionOutcomeEvaluator.evaluate(
            calculated.candidates.single(),
            calculated,
            strategy = null,
            profile = BattleTrainerProfile.balanced(),
        )

        assertTrue(outcome.publiclyInert)
    }

    @Test
    fun `switch execution probability requires the incoming pokemon to survive the known response`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000220")
        val opponentBenchId = UUID.fromString("00000000-0000-0000-0000-000000000221")
        val initial = state(
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
            opponentBench = pokemon(opponentBenchId, BattleSide.OPPONENT, speed = 80, activeSlot = null),
        )
        val switch = BattleActionCandidate(
            actionId = "doomed_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val knockout = move(
            "known_knockout",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.SPECIAL,
            power = 500.0,
        )
        val source = context(
            initial,
            listOf(switch),
            catalog(opponentMoves = listOf(knockout)),
        )

        val evaluated = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(switch)),
            source,
            BattleTrainerProfile.balanced(skillLevel = 2),
            clockMillis = { 0L },
        )

        assertEquals(0.0, evaluated.ranked.single().executionProbability, 1e-9)
    }

    @Test
    fun `move execution probability is not rescued by an opponent switch alternative`() {
        val opponentBenchId = UUID.fromString("00000000-0000-0000-0000-000000000222")
        val initial = state(
            allySpeed = 80,
            opponentSpeed = 120,
            allyHp = 0.10,
            opponentBench = pokemon(opponentBenchId, BattleSide.OPPONENT, speed = 70, activeSlot = null),
        )
        val ownMove = move("too_slow")
        val knockout = move(
            "known_knockout",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.SPECIAL,
            power = 500.0,
        )
        val source = context(
            initial,
            listOf(ownMove),
            catalog(allyMoves = listOf(ownMove), opponentMoves = listOf(knockout)),
        )

        val evaluated = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(ownMove)),
            source,
            BattleTrainerProfile.balanced(skillLevel = 2),
            clockMillis = { 0L },
        )

        assertEquals(0.0, evaluated.ranked.single().executionProbability, 1e-9)
    }

    @Test
    fun `recharge forces wait instead of allowing a move or switch`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000216")
        val initial = state(bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null))
        val history = RecursiveActionHistory(rechargingPokemonIds = setOf(ALLY_ID))

        val actions = PublicFutureActionFactory.actions(initial, BattleSide.ALLY, catalog(), history)

        assertEquals(1, actions.size)
        assertEquals(BattleActionKind.WAIT, actions.single().kind)
    }

    @Test
    fun `taunt encore and trapping constrain only the affected recursive actor`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000217")
        val initial = state(bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null))
        val attack = move("attack")
        val status = move(
            "status",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
        )
        val publicCatalog = catalog(allyMoves = listOf(attack, status))
        val constrained = RecursiveActionHistory(
            tauntTurnsByPokemon = mapOf(ALLY_ID to 2),
            encoreByPokemon = mapOf(ALLY_ID to RecursiveEncoreLock("cobblemon:attack", 2)),
            trappedByPokemon = mapOf(ALLY_ID to RecursiveTrapLock(OPPONENT_ID, 2)),
        )

        val actions = PublicFutureActionFactory.actions(initial, BattleSide.ALLY, publicCatalog, constrained)

        assertEquals(listOf("cobblemon:attack"), actions.mapNotNull { it.moveId })
        assertFalse(actions.any { it.kind == BattleActionKind.SWITCH })
    }

    @Test
    fun `revealed arena trap removes grounded switches but not flying switches`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000219")
        val grounded = state(
            opponentAbility = "cobblemon:arenatrap",
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
        )
        val flying = state(
            allyTypes = setOf("flying"),
            opponentAbility = "cobblemon:arenatrap",
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
        )

        val groundedActions = PublicFutureActionFactory.actions(grounded, BattleSide.ALLY, catalog())
        val flyingActions = PublicFutureActionFactory.actions(flying, BattleSide.ALLY, catalog())

        assertFalse(groundedActions.any { it.kind == BattleActionKind.SWITCH })
        assertTrue(flyingActions.any { it.kind == BattleActionKind.SWITCH })
    }

    @Test
    fun `successful recharge move forces the same actor to wait next turn`() {
        val initial = state()
        val hyperBeam = move(
            "hyper_beam",
            category = BattleMoveDamageCategory.SPECIAL,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.RECHARGE_TURN,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                ),
            ),
        )
        val source = context(initial, listOf(hyperBeam), catalog(allyMoves = listOf(hyperBeam)))
        val outcome = PublicSingleTurnProjector.project(initial, hyperBeam, wait("opponent_wait"), source).first()

        val history = RecursiveHistoryProjector.project(
            RecursiveActionHistory(),
            initial,
            outcome,
            hyperBeam,
            wait("opponent_wait"),
        )
        val nextActions = PublicFutureActionFactory.actions(initial, BattleSide.ALLY, source.publicActionCatalog, history)

        assertEquals(BattleActionKind.WAIT, nextActions.single().kind)
        assertEquals(1, history.moveUses[RecursiveMoveUseKey(ALLY_ID, "cobblemon:hyper_beam")])
    }

    @Test
    fun `successful taunt removes status moves from the targets next recursive turn`() {
        val initial = state()
        val taunt = move(
            "taunt",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.VOLATILE_STATUS,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                    valueId = "taunt",
                ),
            ),
        )
        val opponentAttack = move("opponent_attack", side = BattleSide.OPPONENT)
        val opponentStatus = move(
            "opponent_status",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
        )
        val source = context(
            initial,
            listOf(taunt),
            catalog(opponentMoves = listOf(opponentAttack, opponentStatus)),
        )
        val opponentWait = wait("opponent_wait")
        val outcome = PublicSingleTurnProjector.project(initial, taunt, opponentWait, source).single()

        val history = RecursiveHistoryProjector.project(
            RecursiveActionHistory(),
            initial,
            outcome,
            taunt,
            opponentWait,
        )
        val nextActions = PublicFutureActionFactory.actions(
            initial,
            BattleSide.OPPONENT,
            source.publicActionCatalog,
            history,
        )

        assertEquals(listOf("cobblemon:opponent_attack"), nextActions.mapNotNull { it.moveId })
    }

    @Test
    fun `faster taunt prevents the targets selected status move in the same projected turn`() {
        val initial = state(allySpeed = 140, opponentSpeed = 80)
        val taunt = move(
            "fast_taunt",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.VOLATILE_STATUS,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                    valueId = "taunt",
                ),
            ),
        )
        val opponentDance = move(
            "opponent_dance",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("attack" to 2),
                ),
            ),
        )

        val outcome = PublicSingleTurnProjector.project(
            initial,
            taunt,
            opponentDance,
            context(initial, listOf(taunt)),
        ).single()

        assertEquals(null, outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["attack"])
        assertFalse(BattleSide.OPPONENT in outcome.executedSides)
    }

    @Test
    fun `encore locks the move used before encore resolved`() {
        val initial = state(allySpeed = 80, opponentSpeed = 120)
        val encore = move(
            "encore",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.VOLATILE_STATUS,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                    valueId = "encore",
                ),
            ),
        )
        val firstMove = move("first_move", side = BattleSide.OPPONENT)
        val otherMove = move("other_move", side = BattleSide.OPPONENT)
        val source = context(
            initial,
            listOf(encore),
            catalog(opponentMoves = listOf(firstMove, otherMove)),
        )
        val outcome = PublicSingleTurnProjector.project(initial, encore, firstMove, source).first()

        val history = RecursiveHistoryProjector.project(
            RecursiveActionHistory(),
            initial,
            outcome,
            encore,
            firstMove,
        )
        val nextActions = PublicFutureActionFactory.actions(
            outcome.state,
            BattleSide.OPPONENT,
            source.publicActionCatalog,
            history,
        )

        assertEquals(listOf("cobblemon:first_move"), nextActions.mapNotNull { it.moveId })
    }

    @Test
    fun `faster encore overrides a different selected move in the same projected turn`() {
        val initial = state(allySpeed = 140, opponentSpeed = 80)
        val encore = move(
            "fast_encore",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.VOLATILE_STATUS,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                    valueId = "encore",
                ),
            ),
        )
        val firstMove = move(
            "first_boost",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("attack" to 1),
                ),
            ),
        )
        val selectedOther = move(
            "other_boost",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("defence" to 1),
                ),
            ),
        )
        val source = context(
            initial,
            listOf(encore),
            catalog(opponentMoves = listOf(firstMove, selectedOther)),
        )

        val outcome = PublicSingleTurnProjector.project(
            initial,
            encore,
            selectedOther,
            source,
            RecursiveActionHistory(lastMoveByPokemon = mapOf(OPPONENT_ID to "cobblemon:first_boost")),
        ).single()
        val opponent = outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }

        assertEquals(1, opponent.statStages["attack"])
        assertEquals(null, opponent.statStages["defence"])
        assertEquals("cobblemon:first_boost", outcome.executedMoveIdsByPokemon[OPPONENT_ID])
    }

    @Test
    fun `faster pivot switches immediately so the incoming pokemon receives the later hit`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000218")
        val initial = state(
            allySpeed = 140,
            opponentSpeed = 80,
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
        )
        val voltSwitch = move(
            "volt_switch",
            typeId = "electric",
            category = BattleMoveDamageCategory.SPECIAL,
            power = 40.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.SWITCH_USER,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                ),
            ),
        )
        val opponentHit = move("opponent_hit", side = BattleSide.OPPONENT, power = 60.0)

        val outcomes = PublicSingleTurnProjector.project(
            initial,
            voltSwitch,
            opponentHit,
            context(initial, listOf(voltSwitch)),
        )

        assertTrue(outcomes.all { outcome ->
            val outgoing = outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }
            val incoming = outcome.state.pokemon.single { it.battlePokemonId == benchId }
            outgoing.activeSlot == null && outgoing.hpFraction == 1.0 &&
                incoming.activeSlot == 0 && incoming.hpFraction < 1.0 &&
                BattleSide.ALLY in outcome.switchedSides
        })
    }

    @Test
    fun `switch exposure follows revealed move damage rather than opponent own types`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000215")
        val initial = state(
            opponentTypes = setOf("dark"),
            bench = pokemon(
                benchId,
                BattleSide.ALLY,
                speed = 90,
                activeSlot = null,
                types = setOf("psychic"),
            ),
        )
        val revealedFightingMove = move(
            "revealed_fighting_move",
            side = BattleSide.OPPONENT,
            typeId = "fighting",
            power = 120.0,
        )
        val switch = BattleActionCandidate(
            actionId = "switch_to_psychic",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val source = PublicBattleTacticalCalculator.calculate(
            context(initial, listOf(switch), catalog(opponentMoves = listOf(revealedFightingMove))),
        )

        val outcome = LocalBattleActionOutcomeEvaluator.evaluate(switch, source, null, BattleTrainerProfile.boss())

        assertTrue(requireNotNull(outcome.currentDefensiveExposure) > requireNotNull(outcome.resultingDefensiveExposure))
    }

    @Test
    fun `burn halves physical damage rolls but not special damage rolls`() {
        val healthy = state()
        val burned = state(allyStatus = "cobblemon:burn")
        val physical = move("physical_hit", category = BattleMoveDamageCategory.PHYSICAL)
        val special = move("special_hit", category = BattleMoveDamageCategory.SPECIAL)

        fun rolls(state: BattleStateView, action: BattleActionCandidate) =
            requireNotNull(PublicBattleTacticalCalculator.conservativeDamageRollFractions(
                action,
                PublicBattleTacticalCalculator.calculate(context(state, listOf(action))),
                BattleSide.ALLY,
            ))

        assertTrue(rolls(burned, physical).average() < rolls(healthy, physical).average() * 0.60)
        assertEquals(rolls(healthy, special), rolls(burned, special))
    }

    @Test
    fun `regular poison applies deterministic end turn damage`() {
        val initial = state(allyStatus = "cobblemon:poison")
        val ownWait = wait("ally_wait")

        val outcome = PublicSingleTurnProjector.project(
            initial,
            ownWait,
            wait("opponent_wait"),
            context(initial, listOf(ownWait)),
        ).single()

        assertEquals(15.0 / 16.0, outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `bad poison applies its public base end turn damage`() {
        val initial = state(allyStatus = "cobblemon:tox")
        val ownWait = wait("ally_wait")

        val outcome = PublicSingleTurnProjector.project(
            initial,
            ownWait,
            wait("opponent_wait"),
            context(initial, listOf(ownWait)),
        ).single()

        assertEquals(15.0 / 16.0, outcome.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `bad poison counter increases across recursive turns`() {
        val initial = state(allyStatus = "cobblemon:tox")
        val allyWait = wait("ally_wait")
        val opponentWait = wait("opponent_wait")
        val first = PublicSingleTurnProjector.project(
            initial,
            allyWait,
            opponentWait,
            context(initial, listOf(allyWait)),
        ).single()
        val firstHistory = RecursiveHistoryProjector.project(
            RecursiveActionHistory(),
            initial,
            first,
            allyWait,
            opponentWait,
        )

        val second = PublicSingleTurnProjector.project(
            first.state,
            allyWait,
            opponentWait,
            context(first.state, listOf(allyWait)),
            firstHistory,
        ).single()

        assertEquals(13.0 / 16.0, second.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
    }

    @Test
    fun `bad poison seed uses the most recent public switch as its reset point`() {
        val initial = state(
            turn = 5,
            allyStatus = "cobblemon:tox",
            observedEvents = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = 1,
                    kind = BattleObservedEventKind.STATUS_CHANGED,
                    actorPokemonId = ALLY_ID,
                    publicValueId = "cobblemon:tox",
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = 3,
                    kind = BattleObservedEventKind.SWITCHED,
                    actorPokemonId = ALLY_ID,
                ),
            ),
        )

        val history = RecursiveSnapshotActionConstraints.seed(initial)

        assertEquals(2, history.badPoisonTurnsByPokemon.getValue(ALLY_ID))
    }

    @Test
    fun `bad poison counter resets when the poisoned pokemon switches out and back in`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000217")
        val initial = state(
            allyStatus = "cobblemon:tox",
            bench = pokemon(benchId, BattleSide.ALLY, speed = 90, activeSlot = null),
        )
        val opponentWait = wait("opponent_wait")
        val switchOut = BattleActionCandidate(
            actionId = "switch_out",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val prior = RecursiveActionHistory(badPoisonTurnsByPokemon = mapOf(ALLY_ID to 5))
        val out = PublicSingleTurnProjector.project(
            initial,
            switchOut,
            opponentWait,
            context(initial, listOf(switchOut)),
            prior,
        ).single()
        val afterOut = RecursiveHistoryProjector.project(prior, initial, out, switchOut, opponentWait)
        val switchBack = BattleActionCandidate(
            actionId = "switch_back",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = ALLY_ID,
        )

        val back = PublicSingleTurnProjector.project(
            out.state,
            switchBack,
            opponentWait,
            context(out.state, listOf(switchBack)),
            afterOut,
        ).single()

        assertEquals(15.0 / 16.0, back.state.pokemon.single { it.battlePokemonId == ALLY_ID }.hpFraction, 1e-9)
        assertEquals(1, back.badPoisonTurnsByPokemon.getValue(ALLY_ID))
    }

    @Test
    fun `tailwind persists and creates next turn speed control`() {
        val initial = state(allySpeed = 80, opponentSpeed = 120)
        val tailwind = move(
            id = "tailwind",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SIDE,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.SIDE_CONDITION,
                    target = BattleMoveEffectTarget.USER_SIDE,
                    probability = 1.0,
                    valueId = "cobblemon:tailwind",
                ),
            ),
        )

        val outcome = PublicSingleTurnProjector.project(
            initial,
            tailwind,
            wait("opponent_wait"),
            context(initial, listOf(tailwind)),
        ).single()

        assertEquals(3, outcome.state.field.sideConditions.getValue(BattleSide.ALLY).single().remainingTurns)
        assertEquals(LocalPublicSpeedRelation.ALLY_FIRST, LocalLookaheadStateEvaluator.speedRelation(outcome.state))
    }

    @Test
    fun `opponent close combat self drops improve our leaf value`() {
        val initial = state()
        val closeCombat = move(
            id = "opponent_close_combat",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.PHYSICAL,
            power = 120.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("defence" to -1, "special_defence" to -1),
                ),
            ),
        )
        val plainHit = move(
            id = "opponent_plain_hit",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.PHYSICAL,
            power = 120.0,
        )
        val ownWait = wait("ally_wait")
        val source = context(initial, listOf(ownWait), catalog())

        val droppedOutcomes = PublicSingleTurnProjector.project(initial, ownWait, closeCombat, source)
        val plainOutcomes = PublicSingleTurnProjector.project(initial, ownWait, plainHit, source)

        assertTrue(droppedOutcomes.all { outcome ->
            outcome.state.pokemon.single { it.battlePokemonId == OPPONENT_ID }.statStages["defence"] == -1
        })
        assertTrue(
            droppedOutcomes.sumOf { it.probability * LocalLookaheadStateEvaluator.evaluate(it.state, source) } >
                plainOutcomes.sumOf { it.probability * LocalLookaheadStateEvaluator.evaluate(it.state, source) },
        )
    }

    @Test
    fun `second dragon dance crosses the speed threshold while the first does not`() {
        // 80 * 1.5 = 120 remains slower; 80 * 2.0 = 160 becomes faster than 140.
        val initial = state(allySpeed = 80, opponentSpeed = 140)
        val dragonDance = move(
            id = "dragon_dance",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("attack" to 1, "speed" to 1),
                ),
            ),
        )
        val opponentWait = wait("opponent_wait")
        val source = context(initial, listOf(dragonDance), catalog(allyMoves = listOf(dragonDance)))

        val afterOne = PublicSingleTurnProjector.project(initial, dragonDance, opponentWait, source).single().state
        val afterTwo = PublicSingleTurnProjector.project(afterOne, dragonDance, opponentWait, source).single().state

        assertEquals(BattleSide.OPPONENT, firstMover(afterOne, source))
        assertEquals(BattleSide.ALLY, firstMover(afterTwo, source))
        assertTrue(
            LocalLookaheadStateEvaluator.evaluate(afterTwo, source) >
                LocalLookaheadStateEvaluator.evaluate(afterOne, source),
        )
    }

    @Test
    fun `recursive value uses the expected value of a secondary stat drop`() {
        val initial = state(allySpeed = 120, opponentSpeed = 100)
        val plain = move("plain_fairy_hit", category = BattleMoveDamageCategory.SPECIAL, power = 95.0)
        val moonblast = move(
            id = "moonblast_with_drop",
            category = BattleMoveDamageCategory.SPECIAL,
            power = 95.0,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 0.30,
                    statStages = mapOf("special_attack" to -1),
                ),
            ),
        )
        val source = context(initial, listOf(plain, moonblast), catalog(allyMoves = listOf(plain, moonblast)))

        val result = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(plain), rank(moonblast)),
            source,
            BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY),
        )

        assertTrue(
            result.ranked.single { it.outcome.candidate.actionId == "moonblast_with_drop" }.lookaheadUtility >
                result.ranked.single { it.outcome.candidate.actionId == "plain_fairy_hit" }.lookaheadUtility,
        )
    }

    @Test
    fun `two turn difficulty sees the second dance speed crossover`() {
        val initial = state(allySpeed = 80, opponentSpeed = 140)
        val dragonDance = move(
            id = "dragon_dance",
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STAT_STAGE,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    statStages = mapOf("attack" to 1, "speed" to 1),
                ),
            ),
        )
        val splash = move(
            id = "opponent_splash",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
        )
        val source = context(
            initial,
            listOf(dragonDance),
            catalog(allyMoves = listOf(dragonDance), opponentMoves = listOf(splash)),
        )

        val introductory = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(dragonDance)),
            source,
            BattleTrainerProfile.balanced(0, BattleDifficultyProfiles.INTRODUCTORY),
        )
        val standard = LocalRecursiveLookaheadEvaluator.evaluate(
            listOf(rank(dragonDance)),
            source,
            BattleTrainerProfile.balanced(2, BattleDifficultyProfiles.STANDARD),
        )

        assertEquals(1, introductory.depthCompleted)
        assertEquals(2, standard.depthCompleted)
        assertTrue(standard.ranked.single().lookaheadUtility > introductory.ranked.single().lookaheadUtility)
    }

    @Test
    fun `offensive pressure can justify a healthy switch`() {
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000213")
        val weakHit = move("weak_hit", power = 10.0)
        val strongHit = move("strong_hit", power = 200.0)
        val splash = move(
            id = "opponent_splash",
            side = BattleSide.OPPONENT,
            category = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            targetPattern = BattleMoveTargetPattern.SELF,
        )
        val initial = state(
            bench = pokemon(benchId, BattleSide.ALLY, speed = 100, activeSlot = null),
        )
        val switch = BattleActionCandidate(
            actionId = "offensive_switch",
            kind = BattleActionKind.SWITCH,
            actorSlot = 0,
            switchPokemonId = benchId,
        )
        val publicCatalog = BattlePublicActionCatalogView(
            listOf(
                actionCatalog(ALLY_ID, listOf(weakHit), BattlePublicMoveKnowledge.EXACT_OWN),
                actionCatalog(benchId, listOf(strongHit), BattlePublicMoveKnowledge.EXACT_OWN),
                actionCatalog(OPPONENT_ID, listOf(splash), BattlePublicMoveKnowledge.PUBLICLY_REVEALED),
            ),
        )
        val source = context(initial, listOf(weakHit, switch), publicCatalog)
        val calculated = PublicBattleTacticalCalculator.calculate(source)
        val base = LocalBattleActionPolicy.rank(calculated, null, BattleTrainerProfile.boss())

        val result = LocalRecursiveLookaheadEvaluator.evaluate(base, calculated, BattleTrainerProfile.boss())

        assertEquals(3, base.single { it.outcome.candidate.actionId == "offensive_switch" }.decisionTier)
        assertEquals(
            "offensive_switch",
            result.ranked.first().outcome.candidate.actionId,
            result.ranked.joinToString { rank ->
                "${rank.outcome.candidate.actionId}:base=${rank.outcome.tacticalUtility}," +
                    "total=${rank.comparisonValue},lookahead=${rank.lookaheadUtility}"
            },
        )
    }

    @Test
    fun `switch score rewards taking next turn initiative from a faster opponent`() {
        val fastBenchId = UUID.fromString("00000000-0000-0000-0000-000000000218")
        val slowBenchId = UUID.fromString("00000000-0000-0000-0000-000000000219")
        fun score(benchId: UUID, benchSpeed: Int): Double {
            val initial = state(
                allySpeed = 50,
                opponentSpeed = 100,
                bench = pokemon(benchId, BattleSide.ALLY, speed = benchSpeed, activeSlot = null),
            )
            val switch = BattleActionCandidate(
                actionId = "initiative_switch",
                kind = BattleActionKind.SWITCH,
                actorSlot = 0,
                switchPokemonId = benchId,
            )
            return LocalTacticalScorer.score(switch, context(initial, listOf(switch)))
        }

        assertTrue(score(fastBenchId, 150) > score(slowBenchId, 70))
    }

    private fun firstMover(state: BattleStateView, source: BattleDecisionContext): BattleSide {
        val allyHit = move("ally_order_probe", power = 40.0)
        val opponentHit = move("opponent_order_probe", side = BattleSide.OPPONENT, power = 40.0)
        return PublicSingleTurnProjector.project(state, allyHit, opponentHit, source)
            .map { it.order.first() }
            .distinct()
            .single()
    }

    private fun context(
        state: BattleStateView,
        candidates: List<BattleActionCandidate>,
        catalog: BattlePublicActionCatalogView = catalog(),
    ) = BattleDecisionContext(
        requestId = REQUEST_ID,
        state = state,
        candidates = candidates,
        deadlineEpochMillis = Long.MAX_VALUE,
        publicActionCatalog = catalog,
    )

    private fun state(
        turn: Int = 1,
        allySpeed: Int = 100,
        opponentSpeed: Int = 100,
        allyHp: Double = 1.0,
        allyStatus: String? = null,
        allyAbility: String? = null,
        opponentAbility: String? = null,
        allyTypes: Set<String> = setOf("normal"),
        opponentTypes: Set<String> = setOf("normal"),
        bench: BattlePokemonStateView? = null,
        opponentBench: BattlePokemonStateView? = null,
        allyActionConstraints: BattlePokemonActionConstraintView = BattlePokemonActionConstraintView.empty(),
        observedEvents: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOfNotNull(
            pokemon(
                ALLY_ID,
                BattleSide.ALLY,
                allySpeed,
                hp = allyHp,
                status = allyStatus,
                knownAbility = allyAbility,
                types = allyTypes,
                actionConstraints = allyActionConstraints,
            ),
            pokemon(
                OPPONENT_ID,
                BattleSide.OPPONENT,
                opponentSpeed,
                knownAbility = opponentAbility,
                types = opponentTypes,
            ),
            bench,
            opponentBench,
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to if (bench == null) 1 else 2,
            BattleSide.OPPONENT to if (opponentBench == null) 1 else 2,
        ),
        observedEvents = observedEvents,
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        speed: Int,
        activeSlot: Int? = 0,
        hp: Double = 1.0,
        status: String? = null,
        knownAbility: String? = null,
        types: Set<String> = setOf("normal"),
        speciesId: String = "showdown:test",
        formId: String? = null,
        combatStats: BattleCombatStatRangesView? = null,
        knownFormStates: Map<String, BattlePokemonFormStateView> = emptyMap(),
        actionConstraints: BattlePokemonActionConstraintView = BattlePokemonActionConstraintView.empty(),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = speciesId,
        formId = formId,
        level = 50,
        hpFraction = hp,
        statusId = status,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = knownAbility,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        combatStats = combatStats ?: if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 120, 100, 120, 100, speed)
        } else {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(200, 200),
                attack = BattleIntegerRange(120, 120),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(160, 160),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(speed, speed),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
        knownFormStates = knownFormStates,
        actionConstraints = actionConstraints,
    )

    private fun move(
        id: String,
        side: BattleSide = BattleSide.ALLY,
        typeId: String = "normal",
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        power: Double = 80.0,
        accuracy: Double = 100.0,
        priority: Int = 0,
        currentPp: Int = 10,
        targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED,
        effects: BattleMoveEffectsView? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = if (targetPattern == BattleMoveTargetPattern.SELECTED) {
            listOf(BattleTargetSlot(if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY, 0))
        } else {
            emptyList()
        },
        moveDetails = BattleMoveCandidateView(
            typeId = typeId,
            damageCategory = category,
            power = power,
            accuracy = accuracy,
            priority = priority,
            currentPp = currentPp,
            targetPattern = targetPattern,
            effects = effects,
        ),
    )

    private fun wait(id: String) = BattleActionCandidate(id, BattleActionKind.WAIT)

    private fun effects(vararg effects: BattleMoveEffectView) = BattleMoveEffectsView(
        coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        effects = effects.toList(),
        scriptedBehavior = false,
    )

    private fun rank(candidate: BattleActionCandidate) = LocalBattleActionRank(
        outcome = LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = 0.0,
            expectedDamageFraction = 0.0,
            secureStandardKnockouts = 0,
            executableDamageActions = if (candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) 0 else 1,
            publiclyInert = false,
            entryFaints = false,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
        ),
        decisionTier = 3,
        comparisonValue = 0.0,
    )

    private fun catalog(
        allyMoves: List<BattleActionCandidate> = listOf(move("ally_physical", power = 80.0)),
        opponentMoves: List<BattleActionCandidate> = listOf(
            move(
                "opponent_special",
                side = BattleSide.OPPONENT,
                category = BattleMoveDamageCategory.SPECIAL,
                power = 90.0,
            ),
        ),
    ) = BattlePublicActionCatalogView(
        listOf(
            BattlePokemonActionCatalogView(
                ALLY_ID,
                allyMoves.map { action ->
                    BattlePublicMoveOptionView(
                        requireNotNull(action.moveId),
                        requireNotNull(action.moveDetails),
                        BattlePublicMoveKnowledge.EXACT_OWN,
                    )
                },
                moveSetComplete = true,
            ),
            BattlePokemonActionCatalogView(
                OPPONENT_ID,
                opponentMoves.map { action ->
                    BattlePublicMoveOptionView(
                        requireNotNull(action.moveId),
                        requireNotNull(action.moveDetails),
                        BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                    )
                },
                moveSetComplete = true,
            ),
        ),
    )

    private fun actionCatalog(
        pokemonId: UUID,
        moves: List<BattleActionCandidate>,
        knowledge: BattlePublicMoveKnowledge,
    ) = BattlePokemonActionCatalogView(
        pokemonId,
        moves.map { action ->
            BattlePublicMoveOptionView(
                requireNotNull(action.moveId),
                requireNotNull(action.moveDetails),
                knowledge,
            )
        },
        moveSetComplete = true,
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000202")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000211")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000212")
    }
}
