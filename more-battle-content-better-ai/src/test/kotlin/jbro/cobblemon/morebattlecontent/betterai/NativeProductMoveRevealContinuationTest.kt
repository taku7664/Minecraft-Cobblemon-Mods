package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveOptionView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeProductMoveRevealContinuationTest {
    @Test
    fun `a newly revealed move replaces its inferred slot before the observed turn is replayed`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val inferredDefinition = definition("growl")
            val actualDefinition = definition("protect")
            val inferredRoot = engine.createBattle(inferredDefinition)
            val actualRoot = engine.createBattle(actualDefinition)
            val allyAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, inferredRoot)
                .single { it.moveId == "tackle" && it.mechanic == null }
            val actualAllyAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, actualRoot)
                .single { it.moveId == "tackle" && it.mechanic == null }
            val actualOpponentAction = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, actualRoot)
                .single { it.moveId == "protect" && it.mechanic == null }
            val actualAfter = engine.branch(
                actualRoot.snapshotJson,
                NativeShowdownChoiceEncoder.encode(actualAllyAction, BattleSide.ALLY, actualRoot),
                NativeShowdownChoiceEncoder.encode(actualOpponentAction, BattleSide.OPPONENT, actualRoot),
            )
            val events = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = actualRoot.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "protect",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = actualRoot.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = ALLY,
                    targetPokemonIds = listOf(OPPONENT),
                    publicValueId = "tackle",
                    actorSlot = 0,
                ),
            )
            val initialContext = context(
                inferredRoot,
                publicTemplate(inferredDefinition),
                catalog("growl", BattleOpponentMoveKnowledge.EXPECTED),
            )
            val currentContext = context(
                actualAfter,
                publicTemplate(actualDefinition, events),
                catalog("protect", BattleOpponentMoveKnowledge.CONFIRMED),
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(
                    NativeProductSessionWorld(
                        key = NativeSearchWorldKey("inferred-growl", 0),
                        probability = 1.0,
                        definition = inferredDefinition,
                        rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, inferredRoot),
                        publicContext = initialContext,
                    ),
                ),
                publicTurn = inferredRoot.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = allyAction,
            )
            val reconciler = NativeProductSessionReconciler { _, action -> action(engine) }

            val result = reconciler.reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(
                NativeProductSessionReconcileStatus.AVAILABLE,
                result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}",
            )
            val retained = requireNotNull(result.sessionState).worlds.single()
            assertEquals(listOf("protect"), retained.definition.p2Team.single().moves)
            assertEquals(listOf("protect"), retained.rootSnapshot.frame.p2Team.single().moves.map { it.id })
            assertEquals(
                "protect",
                retained.publicContext.publicActionCatalog.inferredMovesForPokemon(OPPONENT)
                    ?.slots?.single { it.slot == 0 }?.moveId,
            )
            assertTrue(retained.rootSnapshot.frame.log.any { "|move|p2a:" in it && "|Protect|" in it })
        }
    }

    @Test
    fun `a public Tera type rebuilds only unrevealed native slots after the observed turn`(
        @TempDir directory: Path,
    ) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = teraDefinition()
            val root = engine.createBattle(definition)
            val allyAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root)
                .single { it.moveId == "splash" && it.mechanic == null }
            val opponentAction = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root)
                .single { it.moveId == "psychic" && it.mechanic?.mechanicId == "tera" }
            val actualAfter = engine.branch(
                root.snapshotJson,
                NativeShowdownChoiceEncoder.encode(allyAction, BattleSide.ALLY, root),
                NativeShowdownChoiceEncoder.encode(opponentAction, BattleSide.OPPONENT, root),
            )
            val events = listOf(
                BattleObservedEventView(
                    sequence = 1,
                    turn = root.turn,
                    kind = BattleObservedEventKind.TERA_TYPE_REVEALED,
                    actorPokemonId = OPPONENT,
                    publicValueId = "fire",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 2,
                    turn = root.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = OPPONENT,
                    targetPokemonIds = listOf(ALLY),
                    publicValueId = "psychic",
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    sequence = 3,
                    turn = root.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = ALLY,
                    publicValueId = "splash",
                    actorSlot = 0,
                ),
            )
            val initialInference = BattleOpponentMoveInferenceView(OPPONENT, listOf(
                inferred(0, "psychic", BattleOpponentMoveGroup.STAB_ATTACK),
                inferred(1, "tackle", BattleOpponentMoveGroup.COVERAGE_ATTACK),
                guessed(2),
                guessed(3),
            ))
            val currentInference = BattleOpponentMoveInferenceView(OPPONENT, listOf(
                inferred(
                    0,
                    "psychic",
                    BattleOpponentMoveGroup.COVERAGE_ATTACK,
                    BattleOpponentMoveKnowledge.CONFIRMED,
                    BattleOpponentMoveSource.PUBLIC_REVEAL,
                ),
                inferred(1, "flamethrower", BattleOpponentMoveGroup.STAB_ATTACK),
                guessed(2),
                guessed(3),
            ))
            val initialContext = context(
                root,
                publicTemplate(definition),
                catalog(initialInference),
            )
            val currentContext = context(
                actualAfter,
                publicTemplate(definition, events),
                catalog(currentInference, listOf("psychic")),
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("tera-type-rebuild", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                    publicContext = initialContext,
                )),
                publicTurn = root.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = allyAction,
            )

            val result = NativeProductSessionReconciler { _, action -> action(engine) }
                .reconcile(session, currentContext, Long.MAX_VALUE)

            assertEquals(
                NativeProductSessionReconcileStatus.AVAILABLE,
                result.status,
                "root=${result.rootIssues}, observed=${result.observedActionIssues}",
            )
            val retained = requireNotNull(result.sessionState).worlds.single()
            assertEquals(listOf("psychic", "flamethrower"), retained.definition.p2Team.single().moves)
            assertEquals(listOf("psychic", "flamethrower"),
                retained.rootSnapshot.frame.p2Team.single().moves.map { it.id })
            assertEquals(listOf("Fire"), retained.rootSnapshot.frame.p2Active.single().types)
            assertTrue(retained.rootSnapshot.frame.p2Active.single().moves.single { it.id == "psychic" }.pp <
                root.p2Active.single().moves.single { it.id == "psychic" }.pp)
        }
    }

    private fun context(
        frame: NativeBattleFrame,
        template: BattleStateView,
        catalog: BattlePublicActionCatalogView,
    ) = BattleDecisionContext(
        requestId = UUID.nameUUIDFromBytes("request:${frame.snapshotJson}".toByteArray()),
        state = NativeBattleStateAdapter.adapt(frame, template),
        candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame),
        deadlineEpochMillis = Long.MAX_VALUE,
        publicActionCatalog = catalog,
    )

    private fun publicTemplate(
        definition: NativeBattleDefinition,
        events: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = maxOf(1, events.maxOfOrNull(BattleObservedEventView::turn) ?: 1),
        pokemon = listOf(
            publicPokemon(ALLY, BattleSide.ALLY, definition.p1Team.single()),
            publicPokemon(OPPONENT, BattleSide.OPPONENT, definition.p2Team.single()),
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
    ) = BattlePokemonStateView(
        id, side, 0, "cobblemon:${set.species}", null, set.level, 1.0, null,
        emptyMap(), emptySet(), null, null, false, setOf("psychic"),
    )

    private fun catalog(
        moveId: String,
        knowledge: BattleOpponentMoveKnowledge,
    ): BattlePublicActionCatalogView {
        val details = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = if (moveId == "protect") 4 else 0,
            currentPp = 10,
        )
        val source = if (knowledge == BattleOpponentMoveKnowledge.CONFIRMED) {
            BattleOpponentMoveSource.PUBLIC_REVEAL
        } else {
            BattleOpponentMoveSource.LEARNSET_EXPECTATION
        }
        val inference = BattleOpponentMoveInferenceView(
            OPPONENT,
            listOf(
                BattleOpponentMoveSlotView(
                    slot = 0,
                    moveId = moveId,
                    group = BattleOpponentMoveGroup.STATUS_OTHER,
                    knowledge = knowledge,
                    source = source,
                    details = details,
                ),
                guessed(1),
                guessed(2),
                guessed(3),
            ),
        )
        val entries = if (knowledge == BattleOpponentMoveKnowledge.CONFIRMED) {
            listOf(
                BattlePokemonActionCatalogView(
                    OPPONENT,
                    listOf(BattlePublicMoveOptionView(moveId, details, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                ),
            )
        } else {
            emptyList()
        }
        return BattlePublicActionCatalogView(entries, opponentMoveInferences = listOf(inference))
    }

    private fun catalog(
        inference: BattleOpponentMoveInferenceView,
        revealedMoveIds: List<String> = emptyList(),
    ): BattlePublicActionCatalogView = BattlePublicActionCatalogView(
        entries = revealedMoveIds.takeIf(List<String>::isNotEmpty)?.let { moves ->
            listOf(BattlePokemonActionCatalogView(
                OPPONENT,
                moves.map { moveId ->
                    BattlePublicMoveOptionView(
                        moveId,
                        moveDetails(moveId),
                        BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                    )
                },
            ))
        }.orEmpty(),
        opponentMoveInferences = listOf(inference),
    )

    private fun inferred(
        slot: Int,
        moveId: String,
        group: BattleOpponentMoveGroup,
        knowledge: BattleOpponentMoveKnowledge = BattleOpponentMoveKnowledge.EXPECTED,
        source: BattleOpponentMoveSource = BattleOpponentMoveSource.LEARNSET_EXPECTATION,
    ) = BattleOpponentMoveSlotView(slot, moveId, group, knowledge, source, moveDetails(moveId))

    private fun moveDetails(moveId: String): BattleMoveCandidateView = when (moveId) {
        "psychic" -> BattleMoveCandidateView("psychic", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 10)
        "flamethrower" -> BattleMoveCandidateView("fire", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 15)
        "tackle" -> BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 35)
        else -> BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10)
    }

    private fun guessed(slot: Int) = BattleOpponentMoveSlotView(
        slot = slot,
        moveId = null,
        group = BattleOpponentMoveGroup.OTHER,
        knowledge = BattleOpponentMoveKnowledge.GUESS,
        source = BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun definition(opponentMove: String) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(149, 151, 157, 163),
        p1Team = listOf(
            NativePokemonSet(
                "Attacker",
                "Mew",
                listOf("tackle"),
                "synchronize",
                uuid = ALLY.toString(),
                level = 50,
            ),
        ),
        p2Team = listOf(
            NativePokemonSet(
                "Defender",
                "Mew",
                listOf(opponentMove),
                "synchronize",
                uuid = OPPONENT.toString(),
                level = 50,
            ),
        ),
    )

    private fun teraDefinition() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(191, 193, 197, 199),
        p1Team = listOf(NativePokemonSet(
            "Slow Ally",
            "Shuckle",
            listOf("splash"),
            "sturdy",
            uuid = ALLY.toString(),
            level = 50,
        )),
        p2Team = listOf(NativePokemonSet(
            "Tera Opponent",
            "Mew",
            listOf("psychic", "tackle"),
            "synchronize",
            uuid = OPPONENT.toString(),
            teraType = "Fire",
            level = 50,
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
    }
}
