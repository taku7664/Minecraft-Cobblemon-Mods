package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.AiTestDecisionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AiDecisionSnapshotReplayTest {
    @Test
    fun `saved decision contexts survive a json round trip and replay to the same action`() {
        val contexts = mutableListOf<BattleDecisionContext>()
        val definition = LocalSelfPlayMeasurement.definitions(1, seed = 20260927, format = BattleFormat.SINGLE).single()
        LocalTacticalScenarioBattle.run(definition, maximumTurns = 3, recordedContexts = contexts)
        assertTrue(contexts.size >= 2, "The scenario must produce real decision contexts")

        contexts.take(4).forEachIndexed { index, context ->
            val snapshot = AiTestDecisionSnapshot(
                battleId = UUID.nameUUIDFromBytes("snapshot-replay-$index".toByteArray()),
                turn = context.state.turn,
                trainerPersonaId = "replay_test_persona",
                trainerProfile = BattleTrainerProfile.boss(),
                strategy = null,
                nativeContinuation = false,
                capturedAtEpochMillis = 1_000L,
                context = context,
            )
            val json = AiTestDecisionSnapshot.toJson(snapshot)
            val restored = AiTestDecisionSnapshot.fromJson(json)

            assertEquals(json, AiTestDecisionSnapshot.toJson(restored), "Round trip must not lose fields")
            val original = AiDecisionSnapshotReplay.replay(snapshot, nowEpochMillis = 1_000L)
            val replayed = AiDecisionSnapshotReplay.replay(restored, nowEpochMillis = 1_000L)
            assertEquals(original.decision.actionId, replayed.decision.actionId)
            assertEquals(
                original.ranked.map { it.outcome.candidate.actionId to it.comparisonValue },
                replayed.ranked.map { it.outcome.candidate.actionId to it.comparisonValue },
            )
        }
    }

    @Test
    fun `snapshots are written only where a directory was configured`(@TempDir directory: Path) {
        val context = mutableListOf<BattleDecisionContext>().also { recorded ->
            val definition = LocalSelfPlayMeasurement.definitions(1, seed = 7, format = BattleFormat.SINGLE).single()
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 1, recordedContexts = recorded)
        }.first()
        val snapshot = AiTestDecisionSnapshot(
            battleId = UUID.nameUUIDFromBytes("snapshot-write".toByteArray()),
            turn = context.state.turn,
            trainerPersonaId = null,
            trainerProfile = BattleTrainerProfile.balanced(),
            strategy = null,
            nativeContinuation = false,
            capturedAtEpochMillis = 0L,
            context = context,
        )
        val previous = AiTestDecisionSnapshot.configuredDirectory
        try {
            AiTestDecisionSnapshot.configuredDirectory = null
            assertNull(AiTestDecisionSnapshot.write(snapshot), "An unconfigured test run must not write files")

            AiTestDecisionSnapshot.configuredDirectory = directory
            val written = requireNotNull(AiTestDecisionSnapshot.write(snapshot))
            assertTrue(Files.isRegularFile(written))
            assertEquals(snapshot.context.requestId, AiDecisionSnapshotReplay.load(written).context.requestId)
        } finally {
            AiTestDecisionSnapshot.configuredDirectory = previous
        }
    }
}
