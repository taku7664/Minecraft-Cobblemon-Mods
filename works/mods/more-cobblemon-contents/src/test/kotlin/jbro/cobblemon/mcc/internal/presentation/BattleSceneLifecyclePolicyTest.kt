package jbro.cobblemon.mcc.internal.presentation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleSceneLifecyclePolicyTest {
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
