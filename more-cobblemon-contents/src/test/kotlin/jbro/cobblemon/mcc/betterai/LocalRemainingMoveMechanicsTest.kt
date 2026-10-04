package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.zip.ZipInputStream

class LocalRemainingMoveMechanicsTest {
    @Test fun `Substitute survives a small hit and the second hit consumes its remaining HP`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0)
        val created = turns(listOf(user, foe), move("substitute", self = true)).single().stateBeforeResidual
        val once = LocalDirectHitMechanics.apply(created, foe.battlePokemonId, user.battlePokemonId, 0.10, emptyList(), ignoreTargetAbility = false).state
        assertEquals(0.75, once.pokemon.first().hpFraction, 1e-9)
        assertTrue("substitute" in once.pokemon.first().knownVolatileEffectIds)
        val twice = LocalDirectHitMechanics.apply(once, foe.battlePokemonId, user.battlePokemonId, 0.20, emptyList(), ignoreTargetAbility = false).state
        assertFalse("substitute" in twice.pokemon.first().knownVolatileEffectIds)
        assertEquals(0.75, twice.pokemon.first().hpFraction, 1e-9)
    }

    @Test fun `Revival Blessing restores only the requested fainted teammate`() {
        for (maxHp in listOf(200, 201)) {
        val user = mon(BattleSide.ALLY, 0)
        val a = mon(BattleSide.ALLY, null, hp = 0.0)
        val b = mon(BattleSide.ALLY, null, hp = 0.0, maxHp = maxHp)
        val foe = mon(BattleSide.OPPONENT, 0)
        val action = BattleActionCandidate("revive", BattleActionKind.SWITCH, actorSlot = 0,
            switchPokemonId = b.battlePokemonId, tags = setOf("revival_blessing"))
        turns(listOf(user, a, b, foe), action).forEach {
            assertEquals(0.0, it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == a.battlePokemonId }.hpFraction)
            assertEquals((maxHp / 2).toDouble() / maxHp, it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == b.battlePokemonId }.hpFraction, 1e-9)
        }
        }
    }

    @Test fun `Torment and Heal Block become projected restrictions`() {
        for (id in listOf("torment", "healblock")) {
            turns(listOf(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0)), move(id)).forEach {
                assertTrue(id in it.stateBeforeResidual.pokemon.single { p -> p.side == BattleSide.OPPONENT }.knownVolatileEffectIds, id)
            }
        }
    }

    @Test fun `new Heal Block expires after its five residual checks`() {
        var pokemon = turns(listOf(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0)), move("healblock")).single().state.pokemon
        repeat(3) { pokemon = turns(pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single().state.pokemon }
        assertTrue("healblock" in pokemon.last().knownVolatileEffectIds)
        pokemon = turns(pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single().state.pokemon
        assertFalse("healblock" in pokemon.last().knownVolatileEffectIds)
        assertTrue(pokemon.last().knownVolatileEffectIds.none { it.startsWith("healblockturns:") })
    }

    @Test fun `Counter returns the physical damage received earlier this turn`() {
        val user = mon(BattleSide.ALLY, 0, speed = 30)
        val foe = mon(BattleSide.OPPONENT, 0, speed = 150)
        val counter = move("counter", physical = true, priority = -5)
        val hit = move("tackle", physical = true, power = 40.0, targetSide = BattleSide.ALLY)
        turns(listOf(user, foe), counter, hit).filter { it.stateBeforeResidual.pokemon.first().hpFraction < 1.0 }.forEach {
            assertTrue(it.stateBeforeResidual.pokemon.last().hpFraction < 1.0, "Counter must hit the attacker")
        }
    }

    @Test fun `Perish Song starts a countdown on both living active teams`() {
        val result = turns(listOf(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0)), move("perishsong", self = true))
        result.forEach { turn ->
            assertTrue(turn.state.pokemon.all { p -> p.knownVolatileEffectIds.any { it.startsWith("perishsong:") } })
        }
    }

    @Test fun `Perish countdown reaches zero after three further turns and a switch escapes it`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0)
        var pokemon = turns(listOf(user, foe), move("perishsong", self = true)).single().state.pokemon
        repeat(2) { pokemon = turns(pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single().state.pokemon }
        assertTrue(pokemon.none { it.fainted })
        val final = turns(pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single().state
        assertTrue(final.pokemon.all { it.fainted })
        val replacement = mon(BattleSide.ALLY, null)
        val switch = BattleActionCandidate("escape", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = replacement.battlePokemonId)
        val escaped = turns(pokemon + replacement, switch).single().state
        assertFalse(escaped.pokemon.single { it.battlePokemonId == replacement.battlePokemonId }.fainted)
        assertTrue(escaped.pokemon.single { it.battlePokemonId == replacement.battlePokemonId }.knownVolatileEffectIds.none { it.startsWith("perishsong:") })
    }

    @Test fun `Baton Pass transfers boosts and absolute Substitute HP to a different HP recipient`() {
        val user = LocalPersistentMoveState.withSubstitute(mon(BattleSide.ALLY, 0, maxHp = 400).copyState(statStages = mapOf("atk" to 2)), 0.15)
        val replacement = mon(BattleSide.ALLY, null, maxHp = 200)
        val result = turns(listOf(user, replacement, mon(BattleSide.OPPONENT, 0)), move("batonpass", self = true))
        result.forEach {
            val incoming = it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == replacement.battlePokemonId }
            assertEquals(0, incoming.activeSlot)
            assertEquals(2, incoming.statStages["atk"])
            assertEquals(0.30, LocalPersistentMoveState.substituteFraction(incoming)!!, 1e-9)
        }
    }

    @Test fun `Healing Wish restores a replacement before entry hazard damage`() {
        val user = mon(BattleSide.ALLY, 0)
        val replacement = mon(BattleSide.ALLY, null, hp = 0.3).copyState(statusId = "brn")
        val foe = mon(BattleSide.OPPONENT, 0)
        val prepared = turns(listOf(user, replacement, foe), move("healingwish", self = true)).single().stateBeforeResidual
        assertTrue(prepared.pokemon.first().fainted)
        val incoming = BattleActionCandidate("replace", BattleActionKind.SWITCH, actorSlot = 0,
            switchPokemonId = replacement.battlePokemonId, facts = BattleCandidateFactsView(switchEntryHpLossFraction = 0.25))
        val healed = jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector.project(prepared, BattleSide.ALLY, incoming)
            .pokemon.single { it.battlePokemonId == replacement.battlePokemonId }
        assertEquals(0.75, healed.hpFraction, 1e-9)
        assertNull(healed.statusId)
    }

    @Test fun `Baton Pass copies boosts before the incoming Download callback`() {
        val user = mon(BattleSide.ALLY, 0).copyState(statStages = mapOf("atk" to 2))
        val replacement = mon(BattleSide.ALLY, null).copyState(knownAbilityId = "download")
        turns(listOf(user, replacement, mon(BattleSide.OPPONENT, 0)), move("batonpass", self = true)).forEach {
            val incoming = it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == replacement.battlePokemonId }
            assertEquals(2, incoming.statStages["atk"])
            assertEquals(1, incoming.statStages["special_attack"])
        }
    }

    @Test fun `Baton Pass carries the remaining Heal Block timer and allows it to expire`() {
        val user = mon(BattleSide.ALLY, 0).copyState(knownVolatileEffectIds = setOf("healblock", "healblockturns:2"))
        val replacement = mon(BattleSide.ALLY, null)
        val passed = turns(listOf(user, replacement, mon(BattleSide.OPPONENT, 0)), move("batonpass", self = true)).single()
        val incoming = passed.state.pokemon.single { it.battlePokemonId == replacement.battlePokemonId }
        assertTrue("healblockturns:1" in incoming.knownVolatileEffectIds)
        val expired = turns(passed.state.pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single().state
            .pokemon.single { it.battlePokemonId == replacement.battlePokemonId }
        assertFalse("healblock" in expired.knownVolatileEffectIds)
        assertTrue(expired.knownVolatileEffectIds.none { it.startsWith("healblockturns:") })
    }

    @Test fun `Counter follows the earlier attacker slot after it pivots out`() {
        val user = mon(BattleSide.ALLY, 0)
        val attacker = mon(BattleSide.OPPONENT, null)
        val replacement = mon(BattleSide.OPPONENT, 0)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(user, attacker, replacement),
            BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 2), emptyList(), emptyList())
        val resolved = LocalReactiveMoveState.resolve(state, BattleSide.ALLY, move("counter", physical = true, priority = -5),
            listOf(LocalReceivedMoveHit(attacker.battlePokemonId, user.battlePokemonId, 0.1,
                BattleMoveDamageCategory.PHYSICAL, BattleSide.OPPONENT, 0)))
        assertEquals(listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), resolved.targets)
        assertEquals(BattleIntegerRange(40, 40), resolved.moveDetails!!.effects!!.effects.last().amountRange)
    }

    @Test fun `Bug Bite eats the target Sitrus before its low HP Update can consume it`() {
        val user = mon(BattleSide.ALLY, 0, hp = 0.4)
        val foe = mon(BattleSide.OPPONENT, 0, hp = 0.55).copyState(knownHeldItemId = "sitrusberry")
        turns(listOf(user, foe), move("bugbite", physical = true, power = 60.0)).forEach {
            assertEquals(0.65, it.stateBeforeResidual.pokemon.first().hpFraction, 1e-9)
            assertNull(it.stateBeforeResidual.pokemon.last().knownHeldItemId)
            assertTrue(it.stateBeforeResidual.pokemon.last().hpFraction < 0.55)
        }
    }

    @Test fun `Beak Blast preparation burns contact before the slower attack executes`() {
        val user = mon(BattleSide.ALLY, 0, speed = 20)
        val foe = mon(BattleSide.OPPONENT, 0, speed = 150)
        turns(listOf(user, foe), move("beakblast", physical = true, power = 100.0, priority = -3),
            move("tackle", physical = true, power = 40.0, targetSide = BattleSide.ALLY)).forEach {
            assertEquals("brn", it.stateBeforeResidual.pokemon.last().statusId)
            assertFalse("beakblast" in it.stateBeforeResidual.pokemon.first().knownVolatileEffectIds)
        }
    }

    @Test fun `Rampage preserves both real durations and ends with confusion`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0, maxHp = 5000)
        val first = turns(listOf(user, foe), move("outrage", physical = true, power = 120.0))
        assertEquals(setOf(1, 2), first.mapNotNull { LocalPersistentMoveState.rampageLock(it.state.pokemon.first())?.turns }.toSet())
        assertEquals(1.0, first.sumOf { it.probability }, 1e-9)
        first.forEach { branch ->
            val remaining = LocalPersistentMoveState.rampageLock(branch.state.pokemon.first())!!.turns
            var state = branch.state
            repeat(remaining) { state = LocalPersistentMoveState.afterResidual(state) }
            assertNull(LocalPersistentMoveState.rampageLock(state.pokemon.first()))
            assertTrue("confusion" in state.pokemon.first().knownVolatileEffectIds)
        }
    }

    @Test fun `later hits of a multihit move reach the body after breaking a Substitute`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = LocalPersistentMoveState.withSubstitute(mon(BattleSide.OPPONENT, 0), 0.25)
        val outcomes = turns(listOf(user, foe), move("rockblast", physical = true, power = 100.0))
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach {
            assertTrue(it.stateBeforeResidual.pokemon.last().hpFraction < 1.0)
            assertFalse("substitute" in it.stateBeforeResidual.pokemon.last().knownVolatileEffectIds)
        }
    }

    @Test fun `allied Lightning Rod actually absorbs the redirected hit and gains Special Attack`() {
        val user = mon(BattleSide.ALLY, 0, speed = 100)
        val partner = mon(BattleSide.ALLY, 1, speed = 150).copyState(knownAbilityId = "lightningrod")
        val foe = mon(BattleSide.OPPONENT, 0)
        val bolt = BattleActionCandidate("bolt", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "thunderbolt",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView("electric", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 10,
                BattleMoveTargetPattern.SELECTED_OPPONENT, effects = MOVES["thunderbolt"]))
        turns(listOf(user, partner, foe), bolt).forEach {
            assertEquals(1.0, it.stateBeforeResidual.pokemon.last().hpFraction)
            val absorbed = it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == partner.battlePokemonId }
            assertEquals(1.0, absorbed.hpFraction)
            assertEquals(1, absorbed.statStages["special_attack"])
        }
    }

    @Test fun `Triple Axel stops callbacks at each failed accuracy check`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0, maxHp = 5000).copyState(knownAbilityId = "stamina")
        val outcomes = turns(listOf(user, foe), move("tripleaxel", physical = true, power = 40.0, accuracy = 90.0))
        assertEquals(setOf(0, 1, 2, 3), outcomes.map { it.stateBeforeResidual.pokemon.last().statStages["defence"] ?: 0 }.toSet())
        assertEquals(0.09, outcomes.filter { it.stateBeforeResidual.pokemon.last().statStages["defence"] == 1 }.sumOf { it.probability }, 1e-9)
        assertEquals(0.081, outcomes.filter { it.stateBeforeResidual.pokemon.last().statStages["defence"] == 2 }.sumOf { it.probability }, 1e-9)
    }

    @Test fun `Stamina increases Defence between individual hits and reduces each later damage`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0, maxHp = 5000).copyState(knownAbilityId = "stamina")
        val action = move("rockblast", physical = true, power = 25.0)
        val state = testState(listOf(user, foe))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE, BattleTacticalMemoryView.empty())
        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(action, context, BattleSide.ALLY)!!.sorted()
        val result = LocalMoveHitSequence.project(state, user.battlePokemonId, foe.battlePokemonId, action,
            rolls[(rolls.size - 1) / 2], action.moveDetails!!.effects!!.effects, false, context, BattleSide.ALLY).single()
        val damage = result.receivedHits.map { it.damageFraction }
        assertEquals(3, damage.size)
        assertTrue(damage[0] > damage[1], "Defence gained after hit 1 must reduce hit 2")
        assertTrue(damage[1] > damage[2], "Defence gained after hit 2 must reduce hit 3")
    }

    @Test fun `Stellar boost remains on every hit while its consumed type is recorded`() {
        val user = mon(BattleSide.ALLY, 0).copyState(knownTeraTypeId = "stellar", knownStellarBoostedTypeIds = emptySet())
        val foe = mon(BattleSide.OPPONENT, 0, maxHp = 5000)
        val action = move("rockblast", physical = true, power = 25.0)
        val state = testState(listOf(user, foe))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE, BattleTacticalMemoryView.empty())
        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(action, context, BattleSide.ALLY)!!.sorted()
        val expectedDamage = rolls[(rolls.size - 1) / 2]
        val projected = turns(listOf(user, foe), action).single().stateBeforeResidual
        assertEquals(expectedDamage, 1.0 - projected.pokemon.last().hpFraction, 1e-9)
        assertEquals(setOf("normal"), projected.pokemon.first().knownStellarBoostedTypeIds)
    }

    @Test fun `partial Triple Axel damage uses 20 then 40 power rather than its average power`() {
        val user = mon(BattleSide.ALLY, 0)
        val foe = mon(BattleSide.OPPONENT, 0, maxHp = 5000)
        val action = move("tripleaxel", physical = true, power = 40.0)
        val state = testState(listOf(user, foe))
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE, BattleTacticalMemoryView.empty())
        fun median(candidate: BattleActionCandidate): Double {
            val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, context, BattleSide.ALLY)!!.sorted()
            return rolls[(rolls.size - 1) / 2]
        }
        val average = median(action)
        val expectedFirst = median(move("tackle", physical = true, power = 20.0))
        for (hits in 1..2) {
            val result = LocalMoveHitSequence.project(state, user.battlePokemonId, foe.battlePokemonId, action,
                average * hits, action.moveDetails!!.effects!!.effects, false, context, BattleSide.ALLY, hitCountOverride = hits).single()
            assertEquals(expectedFirst, result.receivedHits.first().damageFraction, 1e-9)
            if (hits == 2) assertEquals(average, result.receivedHits.last().damageFraction, 1e-9)
        }
    }

    @Test fun `Laser Focus lasts through the next turn and expires instead of granting permanent critical hits`() {
        val first = turns(listOf(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0)), move("laserfocus", self = true)).single()
        assertTrue("laserfocusturns:1" in first.state.pokemon.first().knownVolatileEffectIds)
        val second = turns(first.state.pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT)).single()
        assertFalse("laserfocus" in second.state.pokemon.first().knownVolatileEffectIds)
        assertTrue(second.state.pokemon.first().knownVolatileEffectIds.none { it.startsWith("laserfocusturns:") })
    }

    @Test fun `an observed Endure expires and cannot protect a later turn`() {
        val user = mon(BattleSide.ALLY, 0, hp = 0.1).copyState(knownVolatileEffectIds = setOf("endure"))
        val first = turns(listOf(user, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT)).single()
        assertFalse("endure" in first.state.pokemon.first().knownVolatileEffectIds)
        turns(first.state.pokemon, BattleActionCandidate("wait", BattleActionKind.WAIT),
            move("tackle", physical = true, power = 100.0, targetSide = BattleSide.ALLY)).forEach {
            assertTrue(it.stateBeforeResidual.pokemon.first().fainted)
        }
    }

    @Test fun `an observed Destiny Bond persists until its user attempts another move`() {
        val user = mon(BattleSide.ALLY, 0, hp = 0.1, speed = 30).copyState(knownVolatileEffectIds = setOf("destinybond"))
        val foe = mon(BattleSide.OPPONENT, 0, speed = 150)
        val hit = move("tackle", physical = true, power = 100.0, targetSide = BattleSide.ALLY)
        turns(listOf(user, foe), BattleActionCandidate("wait", BattleActionKind.WAIT), hit).forEach {
            assertTrue(it.stateBeforeResidual.pokemon.first().fainted)
            assertTrue(it.stateBeforeResidual.pokemon.last().fainted, "previous-turn Bond must still take down the faster attacker")
        }
        val fast = user.copyState(combatStats = user.combatStats!!.let { BattleCombatStatRangesView(it.maxHp, it.attack,
            it.defence, it.specialAttack, it.specialDefence, BattleIntegerRange(300, 300), it.knowledge) })
        turns(listOf(fast, foe), move("tackle", physical = true, power = 10.0), hit).forEach {
            assertTrue(it.stateBeforeResidual.pokemon.first().fainted)
            assertFalse(it.stateBeforeResidual.pokemon.last().fainted, "a new move attempt must end the old Bond")
        }
    }

    @Test fun `grounded Misty Terrain prevents the confusion at the end of Outrage`() {
        val user = mon(BattleSide.ALLY, 0).copyState(knownVolatileEffectIds = setOf("rampage:outrage:1"))
        val result = turns(listOf(user, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT),
            terrain = "mistyterrain").single().state.pokemon.first()
        assertNull(LocalPersistentMoveState.rampageLock(result))
        assertFalse("confusion" in result.knownVolatileEffectIds)
        assertFalse("confusion" in turns(listOf(user, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT),
            terrain = "mistyterrain", terrainTurns = 1).single().state.pokemon.first().knownVolatileEffectIds)
        val flying = user.copyState(knownTypeIds = setOf("flying"))
        assertTrue("confusion" in turns(listOf(flying, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT),
            terrain = "mistyterrain").single().state.pokemon.first().knownVolatileEffectIds)
    }

    @Test fun `public first Outrage use retains both possible remaining durations`() {
        val user = mon(BattleSide.ALLY, 0).copyState(knownVolatileEffectIds = setOf("rampagepublic:outrage:1"))
        val result = turns(listOf(user, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT))
        assertEquals(setOf(1, 2), result.mapNotNull { LocalPersistentMoveState.rampageLock(it.stateBeforeResidual.pokemon.first())?.turns }.toSet())
        assertEquals(0.5, result.filter { "confusion" in it.state.pokemon.first().knownVolatileEffectIds }.sumOf { it.probability }, 1e-9)
        assertEquals(1.0, result.sumOf { it.probability }, 1e-9)
        val second = user.copyState(knownVolatileEffectIds = setOf("rampagepublic:outrage:2"))
        val final = turns(listOf(second, mon(BattleSide.OPPONENT, 0)), BattleActionCandidate("wait", BattleActionKind.WAIT)).single()
        assertEquals(1, LocalPersistentMoveState.rampageLock(final.stateBeforeResidual.pokemon.first())?.turns)
        assertTrue("confusion" in final.state.pokemon.first().knownVolatileEffectIds)
    }

    private fun turns(pokemon: List<BattlePokemonStateView>, ally: BattleActionCandidate,
                      foe: BattleActionCandidate = BattleActionCandidate("wait", BattleActionKind.WAIT),
                      history: RecursiveActionHistory = RecursiveActionHistory(), terrain: String? = null,
                      terrainTurns: Int = 5): List<jbro.cobblemon.mcc.betterai.state.PublicTurnProjection> {
        val format = if (BattleSide.entries.any { side -> pokemon.count { it.side == side && it.activeSlot != null } > 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE
        val state = BattleStateView(UUID.randomUUID(), format, 1, pokemon,
            BattleFieldStateView(null, terrain?.let { BattleTimedEffectView(it, terrainTurns) }, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
            BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } }, emptyList(), emptyList())
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(ally), Long.MAX_VALUE, BattleTacticalMemoryView.empty())
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        return PublicSingleTurnProjector.project(state, calculated.candidates.single(), foe, calculated, history = history)
    }

    private fun move(id: String, self: Boolean = false, physical: Boolean = false, power: Double = 0.0,
                     priority: Int = 0, targetSide: BattleSide = BattleSide.OPPONENT, accuracy: Double = 100.0) = BattleActionCandidate(
        id, BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = id,
        targets = listOf(BattleTargetSlot(if (self) BattleSide.ALLY else targetSide, 0)),
        moveDetails = BattleMoveCandidateView("normal", if (physical) BattleMoveDamageCategory.PHYSICAL else BattleMoveDamageCategory.STATUS,
            power, accuracy, priority, 10, if (self) BattleMoveTargetPattern.SELF else BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = MOVES[id]))

    private fun mon(side: BattleSide, slot: Int?, hp: Double = 1.0, speed: Int = 100, maxHp: Int = 200) = BattlePokemonStateView(
        UUID.randomUUID(), side, slot, "showdown:probe", null, 50, hp, null, emptyMap(), emptySet(), null, null,
        hp <= 0.0, setOf("normal"), BattleCombatStatRangesView(BattleIntegerRange(maxHp,maxHp), BattleIntegerRange(100,100),
            BattleIntegerRange(100,100), BattleIntegerRange(100,100), BattleIntegerRange(100,100), BattleIntegerRange(speed,speed),
            BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private fun testState(pokemon: List<BattlePokemonStateView>) = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, pokemon,
        BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
        BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } }, emptyList(), emptyList())

    companion object {
        private val MOVES by lazy {
            ZipInputStream(requireNotNull(LocalRemainingMoveMechanicsTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
