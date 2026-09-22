package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageRecipient
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalActsBeforeTargetPowerProjectionTest {
    @Test
    fun `payback doubles only after its target has finished acting`() {
        val boosted = project(
            state(allySpeed = 40, opponentSpeed = 80),
            attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 40, opponentSpeed = 80),
            attack("dark_attack", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(boosted, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `payback stays at base power while its target still has a queued action`() {
        val payback = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("dark_attack", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        val paybackDamage = damage(payback, ALLY, OPPONENT)
        val ordinaryDamage = damage(ordinary, ALLY, OPPONENT)
        assertTrue(paybackDamage > ordinaryDamage * 0.9)
        assertTrue(paybackDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `trick room can make a fast payback act after its slower target and double`() {
        val ordinary = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val underTrickRoom = project(
            state(allySpeed = 120, opponentSpeed = 80, trickRoom = true),
            attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(underTrickRoom, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `payback does not double against a target that switched in`() {
        val initial = state(allySpeed = 40, opponentSpeed = 80, opponentBench = true)
        val payback = project(
            initial,
            attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            switch("opponent_switch", OPPONENT_BENCH),
        )
        val ordinary = project(
            initial,
            attack("dark_attack", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
            switch("opponent_switch", OPPONENT_BENCH),
        )

        val paybackDamage = damage(payback, ALLY, OPPONENT_BENCH)
        val ordinaryDamage = damage(ordinary, ALLY, OPPONENT_BENCH)
        assertTrue(paybackDamage > ordinaryDamage * 0.9)
        assertTrue(paybackDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `follow me lets payback use the redirector that already acted`() {
        val initial = doubleState()
        val payback = project(
            initial,
            joint(
                "payback_pair",
                attack("payback", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )
        val ordinary = project(
            initial,
            joint(
                "ordinary_pair",
                attack("dark_attack", "dark", actorSlot = 0, targetSlot = 0, power = 50.0),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )

        assertTrue(
            damage(payback, ALLY, OPPONENT_PARTNER) >
                damage(ordinary, ALLY, OPPONENT_PARTNER) * 1.8,
        )
    }

    @Test
    fun `avalanche doubles only after its target directly damaged the user`() {
        val boosted = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("avalanche", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("ice_attack", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(boosted, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `revenge uses the same direct attacker requirement`() {
        val boosted = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("revenge", "fighting", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("fighting_attack", "fighting", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(boosted, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `damage from the target's partner does not boost avalanche`() {
        val initial = doubleState()
        val avalanche = project(
            initial,
            joint(
                "avalanche_pair",
                attack("avalanche", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            joint(
                "partner_attack_pair",
                wait("target_wait", actorSlot = 0, targetSide = BattleSide.OPPONENT),
                attack(
                    "partner_attack", "normal", actorSlot = 1, targetSlot = 0,
                    targetSide = BattleSide.ALLY,
                ),
            ),
        )
        val ordinary = project(
            initial,
            joint(
                "ordinary_pair",
                attack("ice_attack", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            joint(
                "partner_attack_pair",
                wait("target_wait", actorSlot = 0, targetSide = BattleSide.OPPONENT),
                attack(
                    "partner_attack", "normal", actorSlot = 1, targetSlot = 0,
                    targetSide = BattleSide.ALLY,
                ),
            ),
        )

        val avalancheDamage = damage(avalanche, ALLY, OPPONENT)
        val ordinaryDamage = damage(ordinary, ALLY, OPPONENT)
        assertTrue(avalancheDamage > ordinaryDamage * 0.9)
        assertTrue(avalancheDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `follow me does not inherit damage dealt by the declared avalanche target`() {
        val initial = doubleState()
        val avalanche = project(
            initial,
            joint(
                "avalanche_pair",
                attack("avalanche", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )
        val ordinary = project(
            initial,
            joint(
                "ordinary_pair",
                attack("ice_attack", "ice", actorSlot = 0, targetSlot = 0, power = 60.0, priority = -4),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )

        val avalancheDamage = damage(avalanche, ALLY, OPPONENT_PARTNER)
        val ordinaryDamage = damage(ordinary, ALLY, OPPONENT_PARTNER)
        assertTrue(avalancheDamage > ordinaryDamage * 0.9)
        assertTrue(avalancheDamage < ordinaryDamage * 1.1)
    }

    @Test
    fun `fishious rend doubles only while the target still has a queued action`() {
        val boosted = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 40, opponentSpeed = 80),
            attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(boosted, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `trick room can turn a slow fishious rend into the doubled hit`() {
        val ordinary = project(
            state(allySpeed = 40, opponentSpeed = 80),
            attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val underTrickRoom = project(
            state(allySpeed = 40, opponentSpeed = 80, trickRoom = true),
            attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(underTrickRoom, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `a target that switches in still takes the doubled hit`() {
        val initial = state(allySpeed = 40, opponentSpeed = 80, opponentBench = true)
        val fishious = project(
            initial,
            attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
            switch("opponent_switch", OPPONENT_BENCH),
        )
        val ordinary = project(
            initial,
            attack("water_attack", "water", actorSlot = 0, targetSlot = 0),
            switch("opponent_switch", OPPONENT_BENCH),
        )

        assertTrue(damage(fishious, ALLY, OPPONENT_BENCH) > damage(ordinary, ALLY, OPPONENT_BENCH) * 1.8)
    }

    @Test
    fun `after you makes the promoted fishious rend use doubled power`() {
        val initial = doubleState()
        val promoted = project(
            initial,
            joint(
                "promoted_pair",
                afterYou(actorSlot = 0, targetSlot = 1),
                attack("fishiousrend", "water", actorSlot = 1, targetSlot = 0),
            ),
            opponentPair(),
        )
        val unpromoted = project(
            initial,
            joint(
                "ordinary_pair",
                wait("ally_wait", actorSlot = 0, targetSide = BattleSide.ALLY),
                attack("fishiousrend", "water", actorSlot = 1, targetSlot = 0),
            ),
            opponentPair(),
        )

        assertTrue(
            damage(promoted, SLOW_ALLY, OPPONENT) > damage(unpromoted, SLOW_ALLY, OPPONENT) * 1.8,
        )
    }

    @Test
    fun `bolt beak uses the same queued target rule`() {
        val boosted = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("boltbeak", "electric", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )
        val ordinary = project(
            state(allySpeed = 120, opponentSpeed = 80),
            attack("electric_attack", "electric", actorSlot = 0, targetSlot = 0),
            attack("opponent_attack", "normal", actorSlot = 0, targetSlot = 0, targetSide = BattleSide.ALLY),
        )

        assertTrue(damage(boosted, ALLY, OPPONENT) > damage(ordinary, ALLY, OPPONENT) * 1.8)
    }

    @Test
    fun `follow me uses the redirector's completed action instead of the declared target's queue`() {
        val initial = doubleState()
        val fishious = project(
            initial,
            joint(
                "fishious_pair",
                attack("fishiousrend", "water", actorSlot = 0, targetSlot = 0),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )
        val ordinary = project(
            initial,
            joint(
                "ordinary_pair",
                attack("water_attack", "water", actorSlot = 0, targetSlot = 0),
                wait("ally_wait", actorSlot = 1, targetSide = BattleSide.ALLY),
            ),
            redirectingOpponentPair(),
        )

        val fishiousDamage = damage(fishious, ALLY, OPPONENT_PARTNER)
        val ordinaryDamage = damage(ordinary, ALLY, OPPONENT_PARTNER)
        assertTrue(fishiousDamage > 0.0)
        assertTrue(fishiousDamage < ordinaryDamage * 1.2)
    }

    private fun project(
        initial: BattleStateView,
        ally: BattleActionCandidate,
        opponent: BattleActionCandidate,
    ): PublicTurnProjection = PublicSingleTurnProjector.project(
        initial,
        ally,
        opponent,
        BattleDecisionContext(UUID.randomUUID(), initial, listOf(ally), Long.MAX_VALUE),
    ).single()

    private fun attack(
        id: String,
        type: String,
        actorSlot: Int,
        targetSlot: Int,
        targetSide: BattleSide = BattleSide.OPPONENT,
        power: Double = 85.0,
        priority: Int = 0,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(targetSide, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = power,
            accuracy = 100.0,
            priority = priority,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scriptedBehavior = id in DYNAMIC_TURN_ORDER_POWER_MOVES,
                mechanicFlags = if (id in DYNAMIC_TURN_ORDER_POWER_MOVES) {
                    setOf("dynamic_base_power", "protect")
                } else {
                    setOf("protect")
                },
            ),
        ),
    )

    private fun afterYou(actorSlot: Int, targetSlot: Int) = BattleActionCandidate(
        actionId = "after_you",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = "afteryou",
        targets = listOf(BattleTargetSlot(BattleSide.ALLY, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 15,
            targetPattern = BattleMoveTargetPattern.SELECTED,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), true),
        ),
    )

    private fun followMe(actorSlot: Int) = BattleActionCandidate(
        actionId = "follow_me",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = "followme",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, actorSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 2,
            currentPp = 20,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), true),
        ),
    )

    private fun wait(id: String, actorSlot: Int, targetSide: BattleSide) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(targetSide, actorSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false),
        ),
    )

    private fun switch(id: String, incoming: UUID) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.SWITCH,
        actorSlot = 0,
        switchPokemonId = incoming,
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun opponentPair() = joint(
        "opponent_pair",
        attack(
            "opponent_attack", "normal", actorSlot = 0, targetSlot = 1,
            targetSide = BattleSide.ALLY,
        ),
        wait("opponent_wait", actorSlot = 1, targetSide = BattleSide.OPPONENT),
    )

    private fun redirectingOpponentPair() = joint(
        "redirecting_opponent_pair",
        attack(
            "opponent_attack", "normal", actorSlot = 0, targetSlot = 0,
            targetSide = BattleSide.ALLY,
        ),
        followMe(actorSlot = 1),
    )

    private fun state(
        allySpeed: Int,
        opponentSpeed: Int,
        trickRoom: Boolean = false,
        opponentBench: Boolean = false,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = buildList {
            add(pokemon(ALLY, BattleSide.ALLY, 0, allySpeed))
            add(pokemon(OPPONENT, BattleSide.OPPONENT, 0, opponentSpeed))
            if (opponentBench) add(pokemon(OPPONENT_BENCH, BattleSide.OPPONENT, null, 60))
        },
        field = field(trickRoom),
        remainingPokemonBySide = mapOf(
            BattleSide.ALLY to 1,
            BattleSide.OPPONENT to if (opponentBench) 2 else 1,
        ),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun doubleState() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.DOUBLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY, BattleSide.ALLY, 0, 120),
            pokemon(SLOW_ALLY, BattleSide.ALLY, 1, 30),
            pokemon(OPPONENT, BattleSide.OPPONENT, 0, 80),
            pokemon(OPPONENT_PARTNER, BattleSide.OPPONENT, 1, 20),
        ),
        field = field(false),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun field(trickRoom: Boolean) = BattleFieldStateView(
        weather = null,
        terrain = null,
        roomEffects = if (trickRoom) listOf(BattleTimedEffectView("trickroom", 3)) else emptyList(),
        globalEffects = emptyList(),
        sideConditions = BattleSide.entries.associateWith { emptyList() },
    )

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, speed: Int) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(400, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(400, 400),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun damage(outcome: PublicTurnProjection, actor: UUID, target: UUID): Double =
        outcome.directDamage.amounts[LocalDirectDamageRecipient(actor, target)] ?: 0.0

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c600")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c601")
        val SLOW_ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c602")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c603")
        val OPPONENT_PARTNER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c604")
        val OPPONENT_BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c605")
        val DYNAMIC_TURN_ORDER_POWER_MOVES = setOf(
            "fishiousrend", "boltbeak", "payback", "avalanche", "revenge",
        )
    }
}
