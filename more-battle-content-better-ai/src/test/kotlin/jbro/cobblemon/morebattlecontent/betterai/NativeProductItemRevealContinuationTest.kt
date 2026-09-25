package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeProductItemRevealContinuationTest {
    @Test
    fun `impossible natural speed order leaves the scarf world and its native move lock`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val scarfDefinition = speedDefinition("choicescarf")
            val plainDefinition = speedDefinition("leftovers")
            val scarfRoot = engine.createBattle(scarfDefinition)
            val plainRoot = engine.createBattle(plainDefinition)
            val allyAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, scarfRoot)
                .single { it.moveId == "splash" && it.mechanic == null }
            val scarfOpponentAction = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, scarfRoot)
                .single { it.moveId == "growl" && it.mechanic == null }
            val plainOpponentAction = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, plainRoot)
                .single { it.moveId == "growl" && it.mechanic == null }

            assertEquals(
                setOf("growl", "splash"),
                NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, scarfRoot)
                    .mapNotNull { it.moveId }.toSet(),
                "A Scarf hypothesis must not start locked before its first action",
            )
            val scarfAfter = engine.branch(
                scarfRoot.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, scarfRoot),
                NativeShowdownChoiceEncoder.encode(scarfOpponentAction, BattleSide.OPPONENT, scarfRoot),
            )
            val plainAfter = engine.branch(
                plainRoot.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, plainRoot),
                NativeShowdownChoiceEncoder.encode(plainOpponentAction, BattleSide.OPPONENT, plainRoot),
            )
            assertEquals(OPPONENT.toString(), scarfAfter.executedMoveOrder.first().pokemonUuid)
            assertEquals(ALLY.toString(), plainAfter.executedMoveOrder.first().pokemonUuid)

            val events = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = 1,
                    kind = BattleObservedEventKind.ACTION_ORDER,
                    actorPokemonId = OPPONENT,
                    publicValueId = "growl",
                    baseMovePriority = 0,
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = 1,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = OPPONENT,
                    targetPokemonIds = listOf(ALLY),
                    publicValueId = "growl",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 3,
                    turn = 1,
                    kind = BattleObservedEventKind.ACTION_ORDER,
                    actorPokemonId = ALLY,
                    publicValueId = "splash",
                    baseMovePriority = 0,
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 4,
                    turn = 1,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = ALLY,
                    publicValueId = "splash",
                    actorSlot = 0,
                ),
            )
            val openingState = speedPublicState(turn = 1)
            val currentState = speedPublicState(turn = 2, events = events, allyAttackStage = -1)
            val currentContext = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("speed-order-current".toByteArray()),
                state = currentState,
                candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, scarfAfter),
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val initialContext = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("speed-order-opening".toByteArray()),
                state = openingState,
                candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, scarfRoot),
                deadlineEpochMillis = Long.MAX_VALUE,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(
                    world(engine.rulesFingerprint, "scarf-world", scarfDefinition, scarfRoot, initialContext),
                    world(engine.rulesFingerprint, "plain-world", plainDefinition, plainRoot, initialContext),
                ),
                publicTurn = 1,
                lastObservedEventSequence = null,
                pendingOwnAction = allyAction,
                trainerTier = BattleTrainerTier.BOSS,
            )

            val result = NativeProductSessionReconciler { _, action -> action(engine) }
                .reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}")
            val retained = requireNotNull(result.sessionState).worlds.single()
            assertEquals("scarf-world", retained.key.hypothesisId)
            assertEquals("choicescarf", retained.definition.p2Team.single().item)
            assertEquals(
                setOf("growl"),
                NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, retained.rootSnapshot.frame)
                    .mapNotNull { it.moveId }.toSet(),
                "The inferred Scarf world must keep Showdown's post-use Choice lock",
            )
        }
    }

    @Test
    fun `public held item reveal removes the conflicting otherwise identical world`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val leftoversDefinition = definition("leftovers")
            val blackSludgeDefinition = definition("blacksludge")
            val leftoversRoot = engine.createBattle(leftoversDefinition)
            val blackSludgeRoot = engine.createBattle(blackSludgeDefinition)
            val allyAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, leftoversRoot)
                .single { it.moveId == "tackle" && it.mechanic == null }
            val opponentAction = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, leftoversRoot)
                .single { it.moveId == "splash" && it.mechanic == null }
            val leftoversAfter = engine.branch(
                leftoversRoot.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, leftoversRoot),
                NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, leftoversRoot),
            )
            val blackSludgeAfter = engine.branch(
                blackSludgeRoot.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, blackSludgeRoot),
                NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, blackSludgeRoot),
            )

            assertEquals(
                leftoversAfter.p2Active.single().hp,
                blackSludgeAfter.p2Active.single().hp,
                "Poison-type Black Sludge and Leftovers must leave the same public HP here",
            )
            assertTrue(leftoversAfter.log.any { "[from] item: Leftovers" in it })
            assertTrue(blackSludgeAfter.log.any { "[from] item: Black Sludge" in it })
            val actionEvents = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = leftoversRoot.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = ALLY,
                    targetPokemonIds = listOf(OPPONENT),
                    publicValueId = "tackle",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = leftoversRoot.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "splash",
                    actorSlot = 0,
                ),
            )
            val leftoversEvents = actionEvents +
                BattleObservedEventView(
                    sequence = 3,
                    turn = leftoversRoot.turn,
                    kind = BattleObservedEventKind.HELD_ITEM_REVEALED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "leftovers",
                    actorSlot = 0,
                )
            val blackSludgeEvents = actionEvents +
                BattleObservedEventView(
                    sequence = 3,
                    turn = blackSludgeRoot.turn,
                    kind = BattleObservedEventKind.HELD_ITEM_REVEALED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "blacksludge",
                    actorSlot = 0,
                )
            val initialLeftoversContext = context(leftoversRoot, publicTemplate(leftoversDefinition))
            val initialBlackSludgeContext = context(blackSludgeRoot, publicTemplate(blackSludgeDefinition))
            val leftoversContext = context(
                leftoversAfter,
                publicTemplate(leftoversDefinition, leftoversEvents, opponentItem = "leftovers"),
            )
            val blackSludgeContext = context(
                blackSludgeAfter,
                publicTemplate(blackSludgeDefinition, blackSludgeEvents, opponentItem = "blacksludge"),
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(
                    world(engine.rulesFingerprint, "leftovers-world", leftoversDefinition, leftoversRoot,
                        initialLeftoversContext),
                    world(engine.rulesFingerprint, "black-sludge-world", blackSludgeDefinition, blackSludgeRoot,
                        initialBlackSludgeContext),
                ),
                publicTurn = leftoversRoot.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = allyAction,
            )
            assertEquals(2, session.worlds.size, "an unrevealed item must remain a hypothesis, not a fact")
            assertNull(
                publicTemplate(leftoversDefinition).pokemon.single { it.battlePokemonId == OPPONENT }.knownHeldItemId,
                "the public state must not expose an item before Showdown reveals it",
            )
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val leftoversResult = reconciler.reconcile(session, leftoversContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, leftoversResult.status,
                "root=${leftoversResult.rootIssues}, observed=${leftoversResult.observedActionIssues}")
            val retainedLeftovers = requireNotNull(leftoversResult.sessionState).worlds.single()
            assertEquals("leftovers-world", retainedLeftovers.key.hypothesisId)
            assertEquals(1.0, retainedLeftovers.probability)
            assertEquals("leftovers", retainedLeftovers.definition.p2Team.single().item)
            assertEquals("leftovers", retainedLeftovers.publicContext.state.pokemon
                .single { it.battlePokemonId == OPPONENT }.knownHeldItemId)

            val blackSludgeResult = reconciler.reconcile(session, blackSludgeContext, Long.MAX_VALUE)

            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, blackSludgeResult.status,
                "root=${blackSludgeResult.rootIssues}, observed=${blackSludgeResult.observedActionIssues}")
            val retainedBlackSludge = requireNotNull(blackSludgeResult.sessionState).worlds.single()
            assertEquals("black-sludge-world", retainedBlackSludge.key.hypothesisId)
            assertEquals(1.0, retainedBlackSludge.probability)
            assertEquals("blacksludge", retainedBlackSludge.definition.p2Team.single().item)
            assertEquals("blacksludge", retainedBlackSludge.publicContext.state.pokemon
                .single { it.battlePokemonId == OPPONENT }.knownHeldItemId)
        }
    }

    private fun context(
        frame: jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame,
        template: BattleStateView,
    ) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("request:${frame.snapshotJson}".toByteArray()),
        state = NativeBattleStateAdapter.adapt(frame, template),
        candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun world(
        rulesFingerprint: String,
        id: String,
        definition: NativeBattleDefinition,
        frame: jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame,
        context: BattleDecisionContext,
    ) = NativeProductSessionWorld(
        key = NativeSearchWorldKey(id, 0),
        probability = 0.5,
        definition = definition,
        rootSnapshot = NativeProductRootSnapshot(rulesFingerprint, frame),
        publicContext = context,
    )

    private fun publicTemplate(
        definition: NativeBattleDefinition,
        events: List<BattleObservedEventView> = emptyList(),
        opponentItem: String? = null,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = maxOf(1, events.maxOfOrNull(BattleObservedEventView::turn) ?: 1),
        pokemon = listOf(
            publicPokemon(ALLY, BattleSide.ALLY, definition.p1Team.single(), item = null),
            publicPokemon(OPPONENT, BattleSide.OPPONENT, definition.p2Team.single(), opponentItem),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun publicPokemon(
        id: UUID,
        side: BattleSide,
        set: NativePokemonSet,
        item: String?,
    ) = BattlePokemonStateView(
        id, side, 0, "cobblemon:${set.species}", null, set.level, 1.0, null,
        emptyMap(), emptySet(), null, item, false,
    )

    private fun definition(item: String) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(127, 131, 137, 139),
        p1Team = listOf(
            NativePokemonSet("Attacker", "Ninjask", listOf("tackle"), "speedboost",
                uuid = ALLY.toString(), level = 50),
        ),
        p2Team = listOf(
            NativePokemonSet("Holder", "Muk", listOf("splash"), "stench",
                uuid = OPPONENT.toString(), item = item, level = 50),
        ),
    )

    private fun speedDefinition(item: String) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(149, 151, 157, 163),
        p1Team = listOf(NativePokemonSet(
            name = "Known Fast",
            species = "Mew",
            moves = listOf("splash"),
            ability = "synchronize",
            uuid = ALLY.toString(),
            nature = "Timid",
            level = 50,
            evs = ZERO_EVS + ("spe" to 252),
            ivs = PERFECT_IVS,
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Possible Scarf",
            species = "Mew",
            moves = listOf("growl", "splash"),
            ability = "synchronize",
            item = item,
            uuid = OPPONENT.toString(),
            nature = "Serious",
            level = 50,
            evs = ZERO_EVS,
            ivs = PERFECT_IVS,
        )),
    )

    private fun speedPublicState(
        turn: Int,
        events: List<BattleObservedEventView> = emptyList(),
        allyAttackStage: Int = 0,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(
            BattlePokemonStateView(
                ALLY, BattleSide.ALLY, 0, "cobblemon:mew", null, 50, 1.0, null,
                if (allyAttackStage == 0) emptyMap() else mapOf("atk" to allyAttackStage),
                if (events.isEmpty()) emptySet() else setOf("splash"),
                null, null, false,
            ),
            BattlePokemonStateView(
                OPPONENT, BattleSide.OPPONENT, 0, "cobblemon:mew", null, 50, 1.0, null,
                emptyMap(), if (events.isEmpty()) emptySet() else setOf("growl"),
                null, null, false,
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = events,
        inferences = emptyList(),
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
