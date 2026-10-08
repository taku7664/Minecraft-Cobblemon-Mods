package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeForcedDamageRoll
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownPublicHp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeDamageInvestmentProductSessionTest {
    @Test
    fun `one public opponent hp percent continues as one world on a supported exact native hp`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = definition("Serious", attackEvs = 0, allyMove = "tackle",
                extraAllyMoves = listOf("seismictoss"))
            val root = engine.createBattle(definition)
            val ownAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root)
                .single { it.moveId == "tackle" && it.mechanic == null }
            val probe = engine.branchWithDamageEvidence(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "tackle"),
                encodedMove(root, BattleSide.OPPONENT, "splash"),
            )
            val roll = probe.executedDamageRolls.single { it.moveId == "tackle" }
            val supported = roll.possibleHpLosses.indices.groupBy { index ->
                NativeShowdownPublicHp.fraction(roll.hpBefore - roll.possibleHpLosses[index], roll.maxHp)
            }.entries.firstOrNull { (_, indexes) ->
                indexes.map { roll.possibleHpLosses[it] }.distinct().size > 1
            } ?: error("Fixture needs distinct native HP values under one public percent")
            val expectedHp = supported.value.mapTo(linkedSetOf()) { index ->
                roll.hpBefore - roll.possibleHpLosses[index]
            }
            val events = listOf(
                BattleObservedEventView(1, 1, BattleObservedEventKind.MOVE_USED, ALLY,
                    publicValueId = "tackle", actorSlot = 0),
                BattleObservedEventView(2, 1, BattleObservedEventKind.HP_CHANGED, OPPONENT,
                    hpFractionDelta = supported.key - 1.0,
                    precedingActionSequence = 1,
                    precedingActionActorPokemonId = ALLY,
                    precedingActionMoveId = "tackle"),
                BattleObservedEventView(3, 1, BattleObservedEventKind.MOVE_USED, OPPONENT,
                    publicValueId = "splash", actorSlot = 0),
            )
            val currentContext = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("rounded-opponent-hp".toByteArray()),
                state = publicState(2, 1.0, supported.key, events, allyKnownMove = "tackle"),
                candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, probe),
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("one-build", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                    publicContext = BattleDecisionContext(
                        requestId = UUID.nameUUIDFromBytes("rounded-opponent-hp-root".toByteArray()),
                        state = publicState(1, 1.0),
                        candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root),
                        deadlineEpochMillis = Long.MAX_VALUE,
                    ),
                )),
                publicTurn = 1,
                lastObservedEventSequence = null,
                pendingOwnAction = ownAction,
                trainerTier = BattleTrainerTier.BOSS,
            )

            val result = NativeProductSessionReconciler { _, action -> action(engine) }
                .reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}, failure=${result.failure}")
            val worlds = requireNotNull(result.sessionState).worlds
            // The fitting rolls differ only in exact HP inside the public rounding: one carries the world on.
            assertEquals(1, worlds.size)
            assertTrue(worlds.single().rootSnapshot.frame.p2Active.single().hp in expectedHp)
            assertEquals(1.0, worlds.sumOf { it.probability }, 1e-9)
        }
    }

    @Test
    fun `rounded damage posterior follows roll support rather than descendant count`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definitions = listOf(0, 8, 16, 24, 32, 48, 64, 96, 128, 160, 192, 252).associate { defenseEvs ->
                defenseEvs.toString() to definition("Serious", 0, "tackle", defenseEvs)
            }
            val roots = definitions.mapValues { (_, definition) -> engine.createBattle(definition) }
            val rolls = roots.mapValues { (_, root) ->
                engine.branchWithDamageEvidence(
                    root.snapshotJson,
                    encodedMove(root, BattleSide.ALLY, "tackle"),
                    encodedMove(root, BattleSide.OPPONENT, "splash"),
                ).executedDamageRolls.single { it.moveId == "tackle" }
            }
            val support = rolls.mapValues { (_, roll) ->
                roll.possibleHpLosses.groupingBy { loss ->
                    NativeShowdownPublicHp.fraction(roll.hpBefore - loss, roll.maxHp)
                }.eachCount()
            }
            val distinctHpCounts = rolls.mapValues { (_, roll) ->
                roll.possibleHpLosses.groupBy { loss ->
                    NativeShowdownPublicHp.fraction(roll.hpBefore - loss, roll.maxHp)
                }.mapValues { (_, losses) -> losses.distinct().size }
            }
            val selected = definitions.keys.toList().flatMapIndexed { index, first ->
                definitions.keys.drop(index + 1).flatMap { second ->
                    support.getValue(first).keys.intersect(support.getValue(second).keys).mapNotNull { percent ->
                        if (support.getValue(first).getValue(percent) != support.getValue(second).getValue(percent) &&
                            distinctHpCounts.getValue(first).getValue(percent) !=
                            distinctHpCounts.getValue(second).getValue(percent)
                        ) {
                            Triple(first, second, percent)
                        } else null
                    }
                }
            }.firstOrNull() ?: error("Fixture needs overlapping public HP with unequal roll support and HP count")
            val (first, second, publicHp) = selected
            val events = listOf(
                BattleObservedEventView(1, 1, BattleObservedEventKind.MOVE_USED, ALLY,
                    publicValueId = "tackle", actorSlot = 0),
                BattleObservedEventView(2, 1, BattleObservedEventKind.HP_CHANGED, OPPONENT,
                    hpFractionDelta = publicHp - 1.0,
                    precedingActionSequence = 1,
                    precedingActionActorPokemonId = ALLY,
                    precedingActionMoveId = "tackle"),
                BattleObservedEventView(3, 1, BattleObservedEventKind.MOVE_USED, OPPONENT,
                    publicValueId = "splash", actorSlot = 0),
            )
            val currentContext = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("unequal-hp-support".toByteArray()),
                state = publicState(2, 1.0, publicHp, events, allyKnownMove = "tackle"),
                candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, roots.getValue(first)),
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(first, second).map { id ->
                    val root = roots.getValue(id)
                    NativeProductSessionWorld(
                        key = NativeSearchWorldKey(id, 0),
                        probability = 0.5,
                        definition = definitions.getValue(id),
                        rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                        publicContext = BattleDecisionContext(
                            requestId = UUID.nameUUIDFromBytes("unequal-hp-root-$id".toByteArray()),
                            state = publicState(1, 1.0),
                            candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root),
                            deadlineEpochMillis = Long.MAX_VALUE,
                        ),
                    )
                },
                publicTurn = 1,
                lastObservedEventSequence = null,
                pendingOwnAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, roots.getValue(first))
                    .single { it.moveId == "tackle" && it.mechanic == null },
                trainerTier = BattleTrainerTier.BOSS,
            )

            val result = NativeProductSessionReconciler { _, action -> action(engine) }
                .reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}, failure=${result.failure}")
            val retained = requireNotNull(result.sessionState).worlds
            val firstSupport = support.getValue(first).getValue(publicHp)
            val secondSupport = support.getValue(second).getValue(publicHp)
            assertEquals(firstSupport.toDouble() / (firstSupport + secondSupport),
                retained.filter { it.key.hypothesisId == first }.sumOf { it.probability }, 1e-9)
            assertEquals(secondSupport.toDouble() / (firstSupport + secondSupport),
                retained.filter { it.key.hypothesisId == second }.sumOf { it.probability }, 1e-9)
        }
    }

    @Test
    fun `public damage removes lower attack builds without reading opponent combat stats`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definitions = linkedMapOf(
                "uninvested" to definition("Serious", attackEvs = 0),
                "partial" to definition("Serious", attackEvs = 128),
                "max" to definition("Adamant", attackEvs = 252),
            )
            val roots = definitions.mapValues { (_, definition) -> engine.createBattle(definition) }
            val probes = roots.mapValues { (_, root) ->
                engine.branchWithDamageEvidence(
                    root.snapshotJson,
                    encodedMove(root, BattleSide.ALLY, "splash"),
                    encodedMove(root, BattleSide.OPPONENT, "doubleedge"),
                )
            }
            val supports = probes.mapValues { (_, frame) ->
                frame.executedDamageRolls.single { it.moveId == "doubleedge" }.possibleHpLosses
            }
            val observedHpLoss = requireNotNull(supports.getValue("max").maxOrNull())
            assertFalse(observedHpLoss in supports.getValue("uninvested"))
            assertFalse(observedHpLoss in supports.getValue("partial"))

            val maxRoot = roots.getValue("max")
            val maxRoll = probes.getValue("max").executedDamageRolls.single { it.moveId == "doubleedge" }
            val forcedMax = engine.branchWithForcedDamage(
                maxRoot.snapshotJson,
                encodedMove(maxRoot, BattleSide.ALLY, "splash"),
                encodedMove(maxRoot, BattleSide.OPPONENT, "doubleedge"),
                listOf(NativeForcedDamageRoll(maxRoll.damageCallIndex, 100)),
            )
            val allyMaxHp = maxRoot.p1Active.single().maxHp
            val opponentMaxHp = maxRoot.p2Active.single().maxHp
            val recoilHpLoss = opponentMaxHp - forcedMax.p2Active.single().hp
            assertTrue(recoilHpLoss > 0, "The integration fixture must exercise damage-based recoil")
            val events = observedTurnEvents(observedHpLoss, allyMaxHp, recoilHpLoss, opponentMaxHp)
            assertTrue(
                kotlin.math.abs(requireNotNull(events[2].hpFractionDelta) +
                    observedHpLoss.toDouble() / allyMaxHp) > 1e-9,
                "This regression must use a rounded public HP delta, not an exact native loss",
            )
            val currentState = publicState(
                turn = 2,
                allyHpFraction = (allyMaxHp - observedHpLoss).toDouble() / allyMaxHp.toDouble(),
                opponentHpFraction = (opponentMaxHp - recoilHpLoss).toDouble() / opponentMaxHp.toDouble(),
                events = events,
            )
            assertNull(
                currentState.pokemon.single { it.battlePokemonId == OPPONENT }.combatStats,
                "Opponent attack investment must remain hidden public input",
            )
            val currentContext = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("damage-investment-current".toByteArray()),
                state = currentState,
                candidates = NativeShowdownRequestActionFactory.actions(
                    BattleSide.ALLY,
                    probes.getValue("max"),
                ),
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = definitions.map { (id, definition) ->
                    val root = roots.getValue(id)
                    NativeProductSessionWorld(
                        key = NativeSearchWorldKey(id, 0),
                        probability = 1.0 / definitions.size.toDouble(),
                        definition = definition,
                        rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                        publicContext = BattleDecisionContext(
                            requestId = UUID.nameUUIDFromBytes("damage-investment-$id".toByteArray()),
                            state = publicState(turn = 1, allyHpFraction = 1.0),
                            candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root),
                            deadlineEpochMillis = Long.MAX_VALUE,
                        ),
                    )
                },
                publicTurn = 1,
                lastObservedEventSequence = null,
                pendingOwnAction = NativeShowdownRequestActionFactory.actions(
                    BattleSide.ALLY,
                    roots.getValue("max"),
                ).single { it.moveId == "splash" && it.mechanic == null },
                trainerTier = BattleTrainerTier.BOSS,
            )

            val result = NativeProductSessionReconciler { _, action -> action(engine) }
                .reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(
                NativeProductSessionReconcileStatus.AVAILABLE,
                result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}, failure=${result.failure}",
            )
            val retained = requireNotNull(result.sessionState).worlds.single()
            assertEquals("max", retained.key.hypothesisId)
            assertEquals(1.0, retained.probability)
            assertEquals("Adamant", retained.definition.p2Team.single().nature)
            assertEquals(252, retained.definition.p2Team.single().evs.getValue("atk"))
            assertEquals(
                allyMaxHp - observedHpLoss,
                retained.rootSnapshot.frame.p1Active.single().hp,
                "Retained native root must use public HP, not its sampled damage roll",
            )
            assertEquals(
                opponentMaxHp - recoilHpLoss,
                retained.rootSnapshot.frame.p2Active.single().hp,
                "Observed-roll replay must recompute damage-based recoil in Showdown",
            )

            val nextRoot = retained.rootSnapshot.frame
            val next = engine.branch(
                nextRoot.snapshotJson,
                encodedMove(nextRoot, BattleSide.ALLY, "splash"),
                encodedMove(nextRoot, BattleSide.OPPONENT, "splash"),
            )
            assertEquals(3, next.turn, "The reconciled root must remain executable by native search")
            assertEquals(retained.rootSnapshot.frame.p1Active.single().hp, next.p1Active.single().hp)
        }
    }

    @Test
    fun `damage no build reaches corrects the opponent attack instead of dropping every world`() {
        EngineBranchWorker().use { engine ->
            val observed = observedMaxAttackTurn(engine)
            val worlds = linkedMapOf(
                "uninvested" to definition("Serious", attackEvs = 0),
                "partial" to definition("Serious", attackEvs = 128),
            )
            val result = reconcileOnEngine(engine, worlds, observed)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}, failure=${result.failure}")
            val retained = requireNotNull(result.sessionState).worlds
            assertEquals(worlds.keys, retained.mapTo(linkedSetOf()) { it.key.hypothesisId })
            retained.forEach { world ->
                val hypothesised = engine.createBattle(world.definition).p2Active.single().stats.getValue("atk")
                val frame = world.rootSnapshot.frame
                assertTrue(frame.p2Active.single().stats.getValue("atk") > hypothesised,
                    "The world must carry a stronger attack than its build")
                assertEquals(observed.allyHpAfter, frame.p1Active.single().hp)
                // The set stays the hypothesis; only the battle reads the corrected stat.
                assertEquals(world.definition.p2Team.single().evs, worlds.getValue(world.key.hypothesisId).p2Team.single().evs)
            }
            val next = retained.first().rootSnapshot.frame.let { frame ->
                engine.branch(frame.snapshotJson, encodedMove(frame, BattleSide.ALLY, "splash"),
                    encodedMove(frame, BattleSide.OPPONENT, "splash"))
            }
            assertEquals(3, next.turn, "A corrected root must stay executable by native search")
        }
    }

    @Test
    fun `a build that explains the damage outweighs one whose attack had to be corrected`() {
        EngineBranchWorker().use { engine ->
            val observed = observedMaxAttackTurn(engine)
            val result = reconcileOnEngine(engine, linkedMapOf(
                "uninvested" to definition("Serious", attackEvs = 0),
                "max" to definition("Adamant", attackEvs = 252),
            ), observed)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status)
            val probability = requireNotNull(result.sessionState).worlds
                .groupBy { it.key.hypothesisId }.mapValues { (_, worlds) -> worlds.sumOf { it.probability } }
            assertEquals(setOf("uninvested", "max"), probability.keys)
            assertTrue(probability.getValue("max") > probability.getValue("uninvested"), probability.toString())
        }
    }

    private class ObservedTurn(val events: List<BattleObservedEventView>, val allyHpAfter: Int, val allyMaxHp: Int,
                               val opponentHpFraction: Double)

    /** One Double-Edge from an Adamant 252 Attack Machamp at its highest roll, as the public saw it. */
    private fun observedMaxAttackTurn(engine: EngineBranchWorker): ObservedTurn {
        val root = engine.createBattle(definition("Adamant", attackEvs = 252))
        val ally = encodedMove(root, BattleSide.ALLY, "splash")
        val opponent = encodedMove(root, BattleSide.OPPONENT, "doubleedge")
        val roll = engine.branchWithDamageEvidence(root.snapshotJson, ally, opponent)
            .executedDamageRolls.single { it.moveId == "doubleedge" }
        val forced = engine.branchWithForcedDamage(root.snapshotJson, ally, opponent,
            listOf(NativeForcedDamageRoll(roll.damageCallIndex, 100)))
        val allyMaxHp = root.p1Active.single().maxHp
        val opponentMaxHp = root.p2Active.single().maxHp
        val loss = allyMaxHp - forced.p1Active.single().hp
        val recoil = opponentMaxHp - forced.p2Active.single().hp
        return ObservedTurn(observedTurnEvents(loss, allyMaxHp, recoil, opponentMaxHp), allyMaxHp - loss, allyMaxHp,
            (opponentMaxHp - recoil).toDouble() / opponentMaxHp)
    }

    private fun reconcileOnEngine(
        engine: EngineBranchWorker,
        definitions: Map<String, NativeBattleDefinition>,
        observed: ObservedTurn,
    ): jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconciliation {
        val roots = definitions.mapValues { (_, definition) -> engine.createBattle(definition) }
        val anyRoot = roots.values.first()
        val session = NativeProductSessionState(
            battleId = BATTLE,
            format = BattleFormat.SINGLE,
            rulesFingerprint = engine.rulesFingerprint,
            worlds = definitions.map { (id, definition) ->
                val root = roots.getValue(id)
                NativeProductSessionWorld(
                    key = NativeSearchWorldKey(id, 0),
                    probability = 1.0 / definitions.size,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                    publicContext = BattleDecisionContext(
                        requestId = UUID.nameUUIDFromBytes("fit-$id".toByteArray()),
                        state = publicState(turn = 1, allyHpFraction = 1.0),
                        candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root),
                        deadlineEpochMillis = Long.MAX_VALUE,
                    ),
                )
            },
            publicTurn = 1,
            lastObservedEventSequence = null,
            pendingOwnAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, anyRoot)
                .single { it.moveId == "splash" && it.mechanic == null },
            trainerTier = BattleTrainerTier.BOSS,
        )
        val current = BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("fit-current".toByteArray()),
            state = publicState(turn = 2, allyHpFraction = observed.allyHpAfter.toDouble() / observed.allyMaxHp,
                opponentHpFraction = observed.opponentHpFraction, events = observed.events),
            candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, anyRoot),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
        return NativeProductSessionReconciler { _, action -> action(engine) }.reconcile(session, current, Long.MAX_VALUE)
    }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame)
            .single { it.moveId == moveId && it.mechanic == null }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun observedTurnEvents(
        hpLoss: Int,
        maxHp: Int,
        recoilHpLoss: Int,
        opponentMaxHp: Int,
    ) = listOf(
        BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = ALLY,
            publicValueId = "splash",
            actorSlot = 0,
        ),
        BattleObservedEventView(
            sequence = 2,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = OPPONENT,
            targetPokemonIds = listOf(ALLY),
            publicValueId = "doubleedge",
            actorSlot = 0,
        ),
        BattleObservedEventView(
            sequence = 3,
            turn = 1,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = ALLY,
            hpFractionDelta = NativeShowdownPublicHp.fraction(maxHp - hpLoss, maxHp) - 1.0,
            precedingActionSequence = 2,
            precedingActionActorPokemonId = OPPONENT,
            precedingActionMoveId = "doubleedge",
        ),
        BattleObservedEventView(
            sequence = 4,
            turn = 1,
            kind = BattleObservedEventKind.HP_CHANGED,
            actorPokemonId = OPPONENT,
            hpFractionDelta = -recoilHpLoss.toDouble() / opponentMaxHp.toDouble(),
            publicSourceEffectId = "recoil",
        ),
    )

    private fun publicState(
        turn: Int,
        allyHpFraction: Double,
        opponentHpFraction: Double = 1.0,
        events: List<BattleObservedEventView> = emptyList(),
        allyKnownMove: String = "splash",
        extraAllyKnownMoves: Set<String> = emptySet(),
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(
            BattlePokemonStateView(
                ALLY, BattleSide.ALLY, 0, "cobblemon:mew", null, 50, allyHpFraction, null,
                emptyMap(), if (events.isEmpty()) emptySet() else setOf(allyKnownMove) + extraAllyKnownMoves,
                "synchronize", null, false, setOf("psychic"),
            ),
            BattlePokemonStateView(
                OPPONENT, BattleSide.OPPONENT, 0, "cobblemon:machamp", null, 50, opponentHpFraction, null,
                emptyMap(), if (events.isEmpty()) emptySet() else setOf("doubleedge"),
                null, null, false, setOf("fighting"),
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun definition(
        nature: String,
        attackEvs: Int,
        allyMove: String = "splash",
        opponentDefenseEvs: Int = 0,
        extraAllyMoves: List<String> = emptyList(),
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(173, 179, 181, 191),
        p1Team = listOf(NativePokemonSet(
            name = "Known defender",
            species = "Mew",
            moves = listOf(allyMove) + extraAllyMoves,
            ability = "synchronize",
            uuid = ALLY.toString(),
            nature = "Serious",
            gender = "N",
            evs = ZERO_EVS,
            ivs = PERFECT_IVS,
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Hidden attacker",
            species = "Machamp",
            moves = listOf("doubleedge", "splash"),
            ability = "noguard",
            uuid = OPPONENT.toString(),
            nature = nature,
            gender = "M",
            evs = ZERO_EVS + ("atk" to attackEvs) + ("def" to opponentDefenseEvs),
            ivs = PERFECT_IVS,
        )),
    )

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip")) {
            "Cobblemon embedded Showdown is missing from the test runtime"
        }
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024) {
                                "Embedded archive exceeds extraction limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return targetRoot.toAbsolutePath().normalize()
    }

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = ZERO_EVS.mapValues { 31 }
    }
}
