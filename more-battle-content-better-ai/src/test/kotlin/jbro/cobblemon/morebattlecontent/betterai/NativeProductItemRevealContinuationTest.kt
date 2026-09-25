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
    }
}
