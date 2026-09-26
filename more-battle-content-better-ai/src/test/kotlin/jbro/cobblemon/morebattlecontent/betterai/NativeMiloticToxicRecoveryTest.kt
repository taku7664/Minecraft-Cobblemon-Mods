package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSearchRunner
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciler
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconciliation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchAggregator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRecursiveSearch
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownSearchTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeMiloticToxicRecoveryTest {
    @Test
    fun `public toxic observation retains the native counter across product decisions`(@TempDir directory: Path) {
        NativeShowdownBranchEngine.open(extractBundledShowdown(directory.resolve("showdown"))).use { engine ->
            val definition = battle()
            val root = engine.createBattle(definition)
            val initial = decisionContext(root, emptyList())
            val initiallyPublicFoe = initial.state.pokemon.single { it.battlePokemonId == BLISSEY }
            assertTrue(initiallyPublicFoe.knownMoveIds.isEmpty())
            assertNull(initiallyPublicFoe.knownAbilityId)
            assertNull(initiallyPublicFoe.knownHeldItemId)
            assertNull(initiallyPublicFoe.combatStats)
            val firstOwnAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root).single {
                it.moveId == "mirrorcoat" && it.mechanic == null
            }
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("milotic-toxic-continuation", 0),
                    probability = 1.0,
                    definition = definition,
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, root),
                    publicContext = initial,
                )),
                publicTurn = root.turn,
                lastObservedEventSequence = null,
                pendingOwnAction = firstOwnAction,
            )
            val expectedFirst = engine.branch(
                root.snapshotJson,
                encodedMove(root, BattleSide.ALLY, "mirrorcoat"),
                encodedMove(root, BattleSide.OPPONENT, "toxic"),
            )
            val firstEvents = observedTurnEvents(root, expectedFirst, 1)
            assertTrue(firstEvents.any {
                it.kind == BattleObservedEventKind.STATUS_CHANGED && it.actorPokemonId == MILOTIC &&
                    it.publicValueId == "tox"
            }, "Native Toxic must emit a public status event: $firstEvents")
            val reconciler = NativeProductSessionReconciler(
                nanoTime = { 0L },
                lease = { _, action -> action(engine) },
            )
            val first = reconciler.reconcile(
                session,
                decisionContext(expectedFirst, firstEvents),
                Long.MAX_VALUE,
            )
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, first.status,
                "The public toxic turn must keep its native world: $first")
            val retained = requireNotNull(first.sessionState).worlds.single().rootSnapshot.frame
            assertEquals("tox", retained.p1Active.single().status)
            assertEquals(expectedFirst.p1Active.single().hp, retained.p1Active.single().hp)

            val secondOwnAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, retained).single {
                it.moveId == "mirrorcoat" && it.mechanic == null
            }
            val expectedSecond = engine.branch(
                expectedFirst.snapshotJson,
                encodedMove(expectedFirst, BattleSide.ALLY, "mirrorcoat"),
                encodedMove(expectedFirst, BattleSide.OPPONENT, "splash"),
            )
            val secondTurnEvents = observedTurnEvents(
                expectedFirst, expectedSecond, firstEvents.last().sequence + 1,
            )
            assertTrue(secondTurnEvents.count { it.kind == BattleObservedEventKind.HP_CHANGED } >= 2,
                "Showdown must expose Leftovers healing and toxic damage as separate public events: " +
                    secondTurnEvents)
            val secondEvents = firstEvents + secondTurnEvents
            val second = reconciler.reconcile(
                requireNotNull(first.sessionState).withPendingOwnAction(secondOwnAction),
                decisionContext(expectedSecond, secondEvents),
                Long.MAX_VALUE,
            )
            assertEquals(NativeProductSessionReconcileStatus.AVAILABLE, second.status,
                "The next product decision must retain the prior toxic counter: $second")
            val twiceRetained = requireNotNull(second.sessionState).worlds.single().rootSnapshot.frame
            assertEquals("tox", twiceRetained.p1Active.single().status)
            assertEquals(expectedSecond.p1Active.single().hp, twiceRetained.p1Active.single().hp)
            assertTrue(twiceRetained.p1Active.single().hp < retained.p1Active.single().hp)
            assertEquals(secondEvents.last().sequence, second.sessionState.lastObservedEventSequence)
        }
    }

    @Test
    fun `bad poison progresses and late Recover loses to a safe switch`(@TempDir directory: Path) {
        NativeShowdownBranchEngine.open(extractBundledShowdown(directory.resolve("showdown"))).use { engine ->
            var frame = engine.createBattle(battle())
            fun turn(ownMove: String, opposingMove: String): NativeBattleFrame = engine.branch(
                frame.snapshotJson,
                encodedMove(frame, BattleSide.ALLY, ownMove),
                encodedMove(frame, BattleSide.OPPONENT, opposingMove),
            ).also { frame = it }

            turn("mirrorcoat", "toxic")
            assertEquals("tox", frame.p1Active.single().status)
            val consecutiveLosses = (1..3).map {
                val before = frame.p1Active.single().hp
                turn("mirrorcoat", "splash")
                before - frame.p1Active.single().hp
            }
            assertTrue(consecutiveLosses.zipWithNext().all { (before, after) -> after > before },
                "The retained native toxic counter must make each passive tick worse: $consecutiveLosses")

            // Recover delays a late toxic KO but cannot reset the counter; a Poison-type bench can.
            turn("recover", "splash")
            turn("mirrorcoat", "splash")
            turn("recover", "splash")
            turn("mirrorcoat", "splash")
            assertEquals("tox", frame.p1Active.single().status)
            assertTrue(frame.p1Active.single().hp > 0)
            assertTrue(frame.p1Active.single().hp * 4 < frame.p1Active.single().maxHp,
                "The fixture must reach a dangerous late-toxic recovery loop")

            val tree = NativeShowdownSearchTree(engine, frame, publicTemplate())
            val search = NativeRecursiveSearch(
                tree = tree,
                world = NativeSearchWorldKey("milotic-late-toxic", 0),
                evaluate = LocalBoardMaterial::evaluate,
                nodeLimit = 400_000,
            ).evaluate(maxDepth = 3)
            assertEquals(3, search.depthCompleted)
            val recover = search.rootValues.single {
                it.action.moveId == "recover" && it.action.mechanic == null
            }
            val switch = search.rootValues.single { it.action.switchPokemonId == ROSERADE }
            assertTrue(switch.value > recover.value,
                "A safe switch must beat late-toxic Recover: " +
                    search.rootValues.map { (it.action.moveId ?: it.action.switchPokemonId) to it.value })
            val ranked = NativeProductRankAdapter.rank(search.rootValues, LocalBoardMaterial.evaluate(tree.root.state))
            val shortlisted = LocalWeightedActionSelector().shortlist(
                ranked,
                LocalActionMixingContext.balanced(0.5).copy(authoritativeSimulationScores = true),
            ).map { it.outcome.candidate }
            assertTrue(shortlisted.none { it.moveId == "recover" },
                "Recover must not remain in the weighted choice pool: " +
                    ranked.map { (it.outcome.candidate.moveId ?: it.outcome.candidate.switchPokemonId) to it.comparisonValue })

            val candidates = tree.actions(tree.root, BattleSide.ALLY)
            val context = BattleDecisionContext(
                requestId = UUID.nameUUIDFromBytes("native-milotic-toxic-recovery".toByteArray()),
                state = tree.root.state,
                candidates = candidates,
                deadlineEpochMillis = 5_000L,
            )
            val session = NativeProductSessionState(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                rulesFingerprint = engine.rulesFingerprint,
                worlds = listOf(NativeProductSessionWorld(
                    key = NativeSearchWorldKey("milotic-late-toxic-product", 0),
                    probability = 1.0,
                    definition = battle(),
                    rootSnapshot = NativeProductRootSnapshot(engine.rulesFingerprint, frame),
                    publicContext = context,
                )),
                publicTurn = context.state.turn,
                lastObservedEventSequence = null,
            )
            val runner = NativeProductSearchRunner(
                nanoTime = { 5_000_000L },
                lease = { _, action -> action(engine) },
            )
            val evaluator = NativeInitialProductDecisionEvaluator(
                planWorlds = { _, _ -> error("The native root must be retained") },
                searchWorlds = NativeProductWorldSearchAggregator(runner::run)::search,
                reconcileSession = { supplied, _, _ -> NativeProductSessionReconciliation(
                    NativeProductSessionReconcileStatus.AVAILABLE, supplied,
                ) },
                nowEpochMillis = { 1_000L },
                nanoTime = { 5_000_000L },
            )
            val evaluation = evaluator.evaluate(
                context, BattleTrainerProfile.boss(), LocalDecisionTuning.CURRENT,
                LocalLookaheadBudget(timeMillis = 2_000L, nodeLimit = 400_000, chanceBranchesPerMove = 1),
                session,
            )
            assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, evaluation.status,
                "Product evaluator must accept the retained toxic native root: $evaluation")
            assertEquals(NativeProductWorldSearchStatus.COMPLETED, evaluation.searchStatus)
            assertEquals(2, evaluation.depthCompleted)
            val productSwitch = evaluation.ranked.single {
                it.outcome.candidate.switchPokemonId == ROSERADE
            }
            val productRecover = evaluation.ranked.single {
                it.outcome.candidate.moveId == "recover" && it.outcome.candidate.mechanic == null
            }
            assertTrue(productSwitch.comparisonValue > productRecover.comparisonValue,
                "The product value path must preserve the safe-switch advantage")
            var productShortlist = emptySet<String>()
            val selector = LocalWeightedActionSelector()
            val brain = LocalTacticalBrain(
                actionSelector = LocalActionSelector { productRanks, seed, mixing ->
                    productShortlist = selector.shortlist(productRanks, mixing)
                        .mapNotNull { it.outcome.candidate.moveId }.toSet()
                    selector.choose(productRanks, seed, mixing)
                },
                nativeInitialDecision = { _, _, _, _, _ -> evaluation },
            )
            val brainSession = brain.openSession(BattleBrainOpenContext(
                battleId = BATTLE,
                format = BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.boss(),
            ))
            val decision = brain.decide(brainSession, context).toCompletableFuture().get()
            assertTrue("recover" !in productShortlist,
                "Product Brain must not sample late-toxic Recover: " +
                    evaluation.ranked.map { (it.outcome.candidate.moveId ?: it.outcome.candidate.switchPokemonId) to it.comparisonValue })
            assertTrue(candidates.single { it.actionId == decision.actionId }.moveId != "recover")
            assertTrue("native_showdown_initial" in decision.tags)
        }
    }

    private fun encodedMove(frame: NativeBattleFrame, side: BattleSide, moveId: String): String {
        val action = NativeShowdownRequestActionFactory.actions(side, frame).single {
            it.moveId == moveId && it.mechanic == null
        }
        return NativeShowdownChoiceEncoder.encode(action, side, frame)
    }

    private fun decisionContext(frame: NativeBattleFrame, events: List<BattleObservedEventView>): BattleDecisionContext {
        val adapted = NativeBattleStateAdapter.adapt(frame, publicTemplate())
        val state = BattleStateView(
            battleId = adapted.battleId,
            format = adapted.format,
            turn = adapted.turn,
            pokemon = adapted.pokemon.map { pokemon ->
                if (pokemon.side == BattleSide.ALLY) pokemon else publicOpponent(pokemon, events)
            },
            field = adapted.field,
            remainingPokemonBySide = adapted.remainingPokemonBySide,
            observedEvents = events,
            inferences = adapted.inferences,
        )
        return BattleDecisionContext(
            requestId = UUID.nameUUIDFromBytes("milotic-toxic-${frame.turn}".toByteArray()),
            state = state,
            candidates = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
    }

    private fun publicOpponent(
        native: BattlePokemonStateView,
        events: List<BattleObservedEventView>,
    ) = BattlePokemonStateView(
        battlePokemonId = native.battlePokemonId,
        side = native.side,
        activeSlot = native.activeSlot,
        speciesId = native.speciesId,
        formId = native.formId,
        level = native.level,
        hpFraction = native.hpFraction,
        statusId = native.statusId,
        statStages = native.statStages,
        knownMoveIds = events.asSequence()
            .filter { it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == native.battlePokemonId }
            .mapNotNull { it.publicValueId?.let { move -> "cobblemon:$move" } }
            .toSet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = native.fainted,
        knownTypeIds = native.knownTypeIds,
        combatStats = null,
    )

    /** Convert only the public Showdown messages relevant to this fixture, in their emitted order. */
    private fun observedTurnEvents(
        before: NativeBattleFrame,
        after: NativeBattleFrame,
        firstSequence: Long,
    ): List<BattleObservedEventView> {
        require(after.log.take(before.log.size) == before.log) { "The native log must be append-only" }
        val events = mutableListOf<BattleObservedEventView>()
        val turn = before.turn
        var ownHp = before.p1Active.single().hp.toDouble() / before.p1Active.single().maxHp
        fun nextSequence() = firstSequence + events.size
        after.log.drop(before.log.size).forEach { line ->
            val fields = line.split('|')
            when (fields.getOrNull(1)) {
                "move" -> {
                    val actor = when {
                        fields.getOrNull(2)?.startsWith("p1a:") == true -> MILOTIC
                        fields.getOrNull(2)?.startsWith("p2a:") == true -> BLISSEY
                        else -> return@forEach
                    }
                    val move = fields.getOrNull(3)?.lowercase()?.filter(Char::isLetterOrDigit)
                        ?.takeIf(String::isNotBlank) ?: return@forEach
                    events += BattleObservedEventView(
                        sequence = nextSequence(), turn = turn, kind = BattleObservedEventKind.MOVE_USED,
                        actorPokemonId = actor, publicValueId = move, actorSlot = 0,
                    )
                }
                "-status" -> if (fields.getOrNull(2)?.startsWith("p1a:") == true) {
                    events += BattleObservedEventView(
                        sequence = nextSequence(), turn = turn, kind = BattleObservedEventKind.STATUS_CHANGED,
                        actorPokemonId = MILOTIC, publicValueId = requireNotNull(fields.getOrNull(3)),
                    )
                }
                "-heal", "-damage" -> if (fields.getOrNull(2)?.startsWith("p1a:") == true) {
                    val hpToken = requireNotNull(fields.getOrNull(3)).substringBefore(' ')
                    val hpParts = hpToken.split('/')
                    require(hpParts.size == 2) { "Expected public HP fraction in $line" }
                    val nextHp = hpParts[0].toDouble() / hpParts[1].toDouble()
                    val source = fields.firstOrNull { it.startsWith("[from] ") }
                        ?.removePrefix("[from] ")
                        ?.substringAfter(':')
                        ?.lowercase()
                        ?.filter(Char::isLetterOrDigit)
                    events += BattleObservedEventView(
                        sequence = nextSequence(), turn = turn, kind = BattleObservedEventKind.HP_CHANGED,
                        actorPokemonId = MILOTIC, hpFractionDelta = nextHp - ownHp,
                        publicSourceEffectId = source,
                    )
                    ownHp = nextHp
                }
            }
        }
        assertTrue(events.isNotEmpty(), "No public events parsed from native turn: ${after.log.drop(before.log.size)}")
        val nativeHp = after.p1Active.single().hp.toDouble() / after.p1Active.single().maxHp
        val publicPercent = kotlin.math.ceil(nativeHp * 100.0)
            .let { if (it == 100.0 && nativeHp < 1.0) 99.0 else it } / 100.0
        assertEquals(publicPercent, ownHp, 1e-9,
            "Showdown's public HP is rounded, not the native exact HP")
        return events
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(439, 443, 449, 457),
        p1Team = listOf(
            NativePokemonSet("Cynthia Milotic", "Milotic",
                listOf("scald", "icebeam", "recover", "mirrorcoat"), "marvelscale", MILOTIC.toString(),
                item = "leftovers", nature = "Bold", teraType = "Water",
                evs = mapOf("hp" to 252, "atk" to 0, "def" to 252, "spa" to 0, "spd" to 0, "spe" to 0)),
            NativePokemonSet("Safe bench", "Roserade", listOf("energyball"), "naturalcure",
                ROSERADE.toString()),
        ),
        p2Team = listOf(NativePokemonSet("Toxic source", "Blissey", listOf("toxic", "splash"),
            "naturalcure", BLISSEY.toString())),
    )

    private fun publicTemplate() = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(MILOTIC, BattleSide.ALLY, 0, "milotic", setOf("water")),
            pokemon(ROSERADE, BattleSide.ALLY, null, "roserade", setOf("grass", "poison")),
            pokemon(BLISSEY, BattleSide.OPPONENT, 0, "blissey", setOf("normal")),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, slot: Int?, species: String, types: Set<String>) =
        BattlePokemonStateView(
            battlePokemonId = id, side = side, activeSlot = slot, speciesId = "cobblemon:$species",
            formId = null, level = 50, hpFraction = 1.0, statusId = null,
            statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
            knownHeldItemId = null, fainted = false, knownTypeIds = types,
        )

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip"))
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024)
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
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001401")
        val MILOTIC: UUID = UUID.fromString("00000000-0000-0000-0000-000000001402")
        val ROSERADE: UUID = UUID.fromString("00000000-0000-0000-0000-000000001403")
        val BLISSEY: UUID = UUID.fromString("00000000-0000-0000-0000-000000001404")
    }
}
