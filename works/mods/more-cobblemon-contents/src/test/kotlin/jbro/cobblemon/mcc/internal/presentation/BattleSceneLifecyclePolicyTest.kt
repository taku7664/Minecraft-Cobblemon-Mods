package jbro.cobblemon.mcc.internal.presentation

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import jbro.cobblemon.mcc.client.BattleScenePlaybackState
import jbro.cobblemon.mcc.internal.compat.cobblemon173.TrainerBodyBattleOwnership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleSceneLifecyclePolicyTest {
    @Test fun `replaced scene callback cannot clear the current scene`() {
        val state = BattleScenePlaybackState()
        val oldCompletion = UUID.randomUUID()
        val currentCompletion = UUID.randomUUID()
        val old = state.begin(oldCompletion)
        val current = state.begin(currentCompletion)

        assertFalse(state.finish(old))
        assertTrue(state.playing)
        assertEquals(currentCompletion, state.completionId)
        assertTrue(state.finish(current))
        assertFalse(state.playing)

        val source = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc/client/BattleSceneClient.kt"))
        val play = source.substringAfter("private fun play(scene: ClientScene)")
        assertTrue(play.indexOf("SceneDialogue.stop()") < play.indexOf("playback.begin(scene.completionId)"))
    }

    @Test fun `stale battle completion cannot dismiss a newer trainer body`() {
        val oldBattle = UUID.randomUUID()
        val currentBattle = UUID.randomUUID()
        assertFalse(TrainerBodyBattleOwnership.matches(currentBattle, oldBattle))
        assertTrue(TrainerBodyBattleOwnership.matches(currentBattle, currentBattle))

        val source = Files.readString(Path.of(
            "src/main/kotlin/jbro/cobblemon/mcc/internal/compat/cobblemon173/Cobblemon173TrainerBody.kt"))
        assertTrue(source.contains("if (!TrainerBodyBattleOwnership.matches(body.battleId, battleId)) return"))
        assertTrue(source.contains("if (bodies.remove(playerId, body)) body.npc.discard()"))
    }

    @Test fun `legacy battle scene codec stays unchanged and awaiting scene requires both channels`() {
        val source = Files.readString(Path.of(
            "src/main/kotlin/jbro/cobblemon/mcc/internal/presentation/BattleScenePayload.kt"))
        val legacy = source.substringBefore("internal data class BattleSceneAwaitPayload")
        assertFalse(legacy.contains("completionId"))
        assertTrue(legacy.contains("StreamCodec.composite("))
        assertTrue(source.contains("\"battle_scene_await\""))
        assertFalse(BattleSceneAwaitPolicy.supported(sceneChannel = false, completionChannel = true))
        assertFalse(BattleSceneAwaitPolicy.supported(sceneChannel = true, completionChannel = false))
        assertTrue(BattleSceneAwaitPolicy.supported(sceneChannel = true, completionChannel = true))

        val api = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc/api/presentation/BattleScenes.kt"))
        assertTrue(api.contains("ServerPlayNetworking.canSend(player, BattleSceneAwaitPayload.TYPE)"))
        assertTrue(api.contains("BattleSceneFinishedPayload.TYPE.id() in ServerPlayNetworking.getReceived(player)"))
    }

    @Test fun `scene completion has no elapsed-time exit and closes on lost context`() {
        assertEquals(BattleSceneCloseReason.NONE, BattleSceneCompletionPolicy.reason(true, true, true, 20.0))
        assertEquals(BattleSceneCloseReason.PLAYER_GONE, BattleSceneCompletionPolicy.reason(false, true, true, 0.0))
        assertEquals(BattleSceneCloseReason.FOCUS_GONE, BattleSceneCompletionPolicy.reason(true, false, true, 0.0))
        assertEquals(BattleSceneCloseReason.CHANGED_LEVEL, BattleSceneCompletionPolicy.reason(true, true, false, 0.0))
        assertEquals(BattleSceneCloseReason.TOO_FAR,
            BattleSceneCompletionPolicy.reason(true, true, true, BattleSceneCompletionPolicy.MAX_DISTANCE_SQUARED + 1.0))
    }

    @Test fun `held managed battle reads showdown id only after the hold starts`() {
        val engine = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc/internal/compat/cobblemon173/Cobblemon173ManagedAiBattleEngine.kt"))
        assertTrue(engine.contains("BattleEntryHold.whenStarted(battle.battleId) {\n                    sceneDirector.watch(battle, trainerActor.showdownId)"))
        assertTrue(engine.contains("catch (failure: RuntimeException) {\n        Cobblemon173TrainerBody.dismiss(prepared.playerId)"))

        val client = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc/client/BattleSceneClient.kt"))
        assertFalse(client.contains("HOLD_LIMIT_MILLIS"))
        assertFalse(client.contains("NARRATION_WAIT_MILLIS"))
    }
}
