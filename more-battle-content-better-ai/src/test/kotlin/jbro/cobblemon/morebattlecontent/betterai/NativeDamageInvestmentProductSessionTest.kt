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
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeForcedDamageRoll
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeDamageInvestmentProductSessionTest {
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
            hpFractionDelta = -hpLoss.toDouble() / maxHp.toDouble(),
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
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOf(
            BattlePokemonStateView(
                ALLY, BattleSide.ALLY, 0, "cobblemon:mew", null, 50, allyHpFraction, null,
                emptyMap(), if (events.isEmpty()) emptySet() else setOf("splash"),
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

    private fun definition(nature: String, attackEvs: Int) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(173, 179, 181, 191),
        p1Team = listOf(NativePokemonSet(
            name = "Known defender",
            species = "Mew",
            moves = listOf("splash"),
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
            evs = ZERO_EVS + ("atk" to attackEvs),
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
