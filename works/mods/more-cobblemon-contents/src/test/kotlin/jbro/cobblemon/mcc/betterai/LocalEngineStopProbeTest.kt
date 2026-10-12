package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate
import jbro.cobblemon.mcc.betterai.matchup.StopTool
import jbro.cobblemon.mcc.betterai.matchup.StopToolKind
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * What an opponent's move does to a Swords Dance, played out in the engine. A recorded Boss position (Garchomp in
 * front of Tapu Koko, which knows Wild Charge and is expected to know Brave Bird), rebuilt from the public board with
 * Garchomp's HP set, and one move given to Tapu Koko as revealed. No move is named in the code under test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalEngineStopProbeTest {
    private val worker = EngineBranchWorker()

    @BeforeAll
    fun engine() {
        LocalEngineStopProbe.lease = object : LocalEngineStopProbe.Lease {
            override fun <T> withWorker(deadlineNanos: Long, action: (NativeBranchWorker) -> T): T? = action(worker)
        }
    }

    @Test
    fun `a forced switch stops the setup whatever the duel`() {
        val (tools, failures) = probe("roar", hp = 1.0)
        assertEquals(StopToolKind.FORCES_SWITCH, tools.single { it.moveId == "roar" }.kind)
        assertEquals(1.0, tools.single { it.moveId == "roar" }.value, 1e-9)
        assertTrue(failures.any { it.endsWith(":forces_switch") }, failures.toString())
    }

    @Test
    fun `Destiny Bond on the turn the boosted sweeper attacks stops it`() {
        val (tools, failures) = probe("destinybond", hp = 1.0)
        assertEquals(StopToolKind.DESTINY_BOND, tools.single { it.moveId == "destinybond" }.kind)
        assertTrue(failures.any { it.endsWith(":destiny_bond") }, failures.toString())
    }

    @Test
    fun `Taunt is read as a block and does not stop a sweeper that wins anyway`() {
        val (tools, failures) = probe("taunt", hp = 1.0)
        val taunt = tools.single { it.moveId == "taunt" }
        assertEquals(StopToolKind.TAUNT, taunt.kind)
        assertEquals(0.0, taunt.value, 1e-9)
        assertFalse(failures.any { it.startsWith("stopper:") }, failures.toString())
    }

    @Test
    fun `Encore stops a sweeper it can lock while it is weakened, not one at full health`() {
        assertEquals(0.0, probe("encore", hp = 1.0).first.single { it.moveId == "encore" }.value, 1e-9)
        assertTrue(probe("encore", hp = 0.5).first.single { it.moveId == "encore" }.value > 0.5)
    }

    @Test
    fun `Thunder Wave on a Ground type and Haze from the faster side stop nothing`() {
        assertEquals(0.0, probe("thunderwave", hp = 1.0).first.single { it.moveId == "thunderwave" }.value, 1e-9)
        assertEquals(0.0, probe("haze", hp = 1.0).first.single { it.moveId == "haze" }.value, 1e-9)
    }

    /** The probe's tools for Tapu Koko against Garchomp's Swords Dance, and the setup gate's failures. */
    private fun probe(extra: String, hp: Double): Pair<List<StopTool>, List<String>> {
        val raw = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/garchomp-mega-earthquake-drawn-plain.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }.let(AiTestDecisionSnapshot::fromJson).context
        val foeSpecies = raw.state.pokemon.first { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }.speciesId.substringAfter(':')
        val preview = requireNotNull(raw.opponentTeamPreview)
        val withPool = BattleOpponentTeamPreviewView(preview.selectionSize, preview.pokemon.map { p ->
            val pool = p.moveCandidatePool
            if (pool == null || p.speciesId.substringAfter(':') != foeSpecies) p else BattleOpponentTeamPreviewPokemonView(
                previewSlotId = p.previewSlotId, speciesId = p.speciesId, formId = p.formId, level = p.level,
                knownTypeIds = p.knownTypeIds, combatStats = p.combatStats, knownFormStates = p.knownFormStates,
                moveCandidatePool = BattleOpponentPreviewMovePoolView(pool.speciesId, pool.formId, pool.moveIds + extra, pool.sourceId,
                pool.moveDetails + (extra to BattleMoveCandidateView("Normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
                    if (META.getValue(extra) == "self") BattleMoveTargetPattern.SELF else BattleMoveTargetPattern.SELECTED_OPPONENT,
                    MOVES[extra]))),
                buildCandidatePool = p.buildCandidatePool, showdownSpeciesId = p.showdownSpeciesId)
        })
        // Rebuilt from the public board (no live state), Garchomp at the HP the case sets.
        val twisted = raw.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L, opponentTeamPreview = withPool,
            state = raw.state.derive(pokemon = raw.state.pokemon.map {
                when {
                it.side == BattleSide.OPPONENT && it.activeSlot == 0 -> it.copyState(knownMoveIds = it.knownMoveIds + extra)
                it.side == BattleSide.ALLY && it.activeSlot == 0 -> it.copyState(hpFraction = hp)
                else -> it
                }
            })).withShowdownState(null)
        val tier = BattleTrainerTier.BOSS
        val foeId = twisted.state.pokemon.first { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }.battlePokemonId
        val catalog = twisted.publicActionCatalog.withOpponentMoveInferences(
            twisted.publicActionCatalog.opponentMoveInferences.map { inference ->
                if (inference.battlePokemonId != foeId) inference else {
                val replaced = inference.slots.lastOrNull { it.knowledge != BattleOpponentMoveKnowledge.CONFIRMED &&
                    it.group != BattleOpponentMoveGroup.STAB_ATTACK && it.group != BattleOpponentMoveGroup.COVERAGE_ATTACK }
                    ?: inference.slots.last()
                BattleOpponentMoveInferenceView(foeId, inference.slots.map {
                    if (it !== replaced) it else BattleOpponentMoveSlotView(it.slot, extra, BattleOpponentMoveGroup.STATUS_OTHER,
                        BattleOpponentMoveKnowledge.CONFIRMED, BattleOpponentMoveSource.PUBLIC_REVEAL,
                        withPool.pokemon.first { p -> p.speciesId.substringAfter(':') == foeSpecies }.moveCandidatePool!!.moveDetails[extra])
                })
                }
            })
        val bound = twisted.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(twisted.state, catalog, tier,
            LocalOpponentMoveUsage.forFormat(twisted.state.format)))
        val context = PublicBattleTacticalCalculator.calculate(LocalOpponentStatAssumption.applyToPublicState(bound, tier), observedBoard = true)
        val scores = LocalMatchupScoreCalculator.calculate(context)
        val sweeper = context.state.pokemon.first { it.side == BattleSide.ALLY && it.activeSlot == 0 }
        val foe = context.state.pokemon.first { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
        val setup = context.candidates.first { it.moveId == "swordsdance" && it.mechanic == null }
        val tools = LocalEngineStopProbe.tools(context, tier, sweeper, foe, LocalEngineStopProbe.Opening.Move(setup), scores)
        val failures = LocalSetupGate.evaluate(setup, context, scores, tier = tier)?.failures.orEmpty()
        println("STOP PROBE $extra hp=$hp ${tools.map { "${it.moveId}:${it.kind}:${it.value}" }} $failures")
        return tools to failures
    }

    private companion object {
        val SOURCE: String by lazy {
            java.util.zip.ZipInputStream(requireNotNull(LocalEngineStopProbeTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/moves.js" }
                zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val MOVES: Map<String, BattleMoveEffectsView> by lazy { BattleDeclarativeMoveEffects.parse(SOURCE) }
        val META: Map<String, String> by lazy {
            Regex("""
  ([a-z0-9]+): \{([\s\S]*?)
  \}""").findAll(SOURCE).associate { m ->
                m.groupValues[1] to (Regex("""
    target: "([A-Za-z]+)"""").find(m.groupValues[2])?.groupValues?.get(1) ?: "normal")
            }
        }
    }
}
