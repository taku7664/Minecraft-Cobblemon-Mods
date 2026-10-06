package jbro.cobblemon.mcc.client

import jbro.cobblemon.battlecam.api.BattlecamScenes
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.presentation.BattleScenePayload
import jbro.cobblemon.ui.extended.BattleDialogue
import jbro.cobblemon.ui.extended.SceneDialogue
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.Util

/**
 * Plays the server's battle scenes: the lines in Cobblemon UI's message box and, with Better Cobblemon Battlecam
 * installed, the camera on the speaker until the last line is gone. The server ends a battle before it names the
 * winner, so a battle's end is held for a moment in case its closing scene follows, and then until that scene is over:
 * the battle closes after its last words, not before them.
 */
object BattleSceneClient {
    /** How long a battle's end waits for a closing scene that may follow it. */
    private const val SCENE_GRACE_MILLIS = 500L
    /** A held end never waits longer than this, whatever the scene is doing. */
    private const val HOLD_LIMIT_MILLIS = 90_000L
    /** Battle narration still on screen goes first, unless the player leaves it unread this long. */
    private const val NARRATION_WAIT_MILLIS = 8_000L
    /** The camera is let go when the lines end; this only bounds it if that never comes. */
    private const val CAMERA_LIMIT_MILLIS = 120_000L

    private var pending: BattleScenePayload? = null
    private var pendingSince = 0L
    private var playing = false
    private var heldEnd: Runnable? = null
    private var heldSince = 0L
    private var replaying = false

    internal fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleScenePayload.TYPE) { payload, context ->
            context.client().execute {
                pending = payload
                pendingSince = Util.getMillis()
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ -> tick() }
        MccClientSessionReset.onReset("battle scene") {
            pending = null
            heldEnd = null
            SceneDialogue.stop()
        }
    }

    /**
     * Called as the battle-end packet is handled: true when the end is held and [replay] will finish it later, after
     * a closing scene or a short wait for one.
     */
    @JvmStatic
    fun holdBattleEnd(replay: Runnable): Boolean {
        if (replaying) return false
        heldEnd = replay
        heldSince = Util.getMillis()
        return true
    }

    private fun tick() {
        val now = Util.getMillis()
        if (heldEnd != null && (now - heldSince > HOLD_LIMIT_MILLIS ||
                pending == null && !playing && now - heldSince > SCENE_GRACE_MILLIS)) {
            if (playing) SceneDialogue.stop() // Its finish lets the end go.
            releaseEnd()
        }
        val scene = pending ?: return
        if (BattleDialogue.hasPending() && now - pendingSince < NARRATION_WAIT_MILLIS) return
        pending = null
        play(scene)
    }

    private fun play(scene: BattleScenePayload) {
        var focused = false
        playing = true
        SceneDialogue.play(scene.speaker, scene.lines) {
            if (focused) Battlecam.release()
            playing = false
            releaseEnd()
        }
        if (scene.focusEntityId != BattleScenePayload.NO_FOCUS && SceneDialogue.isActive()) {
            focused = Battlecam.focus(scene.focusEntityId, CAMERA_LIMIT_MILLIS)
        }
    }

    private fun releaseEnd() {
        val end = heldEnd ?: return
        heldEnd = null
        replaying = true
        try {
            end.run()
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("A held battle end failed to finish", failure)
        } finally {
            replaying = false
        }
    }

    /** Better Cobblemon Battlecam is optional; its classes are only touched when it is installed. */
    private object Battlecam {
        private val installed = FabricLoader.getInstance().isModLoaded("better_cobblemon_battlecam")

        fun focus(entityId: Int, millis: Long): Boolean = installed && guarded(false) { BattlecamScenes.focus(entityId, millis) }

        fun release() {
            if (installed) guarded(Unit) { BattlecamScenes.release() }
        }

        private inline fun <T> guarded(fallback: T, action: () -> T): T = try {
            action()
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.warn("Battle scene camera failed", failure)
            fallback
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.warn("Battle scene camera is not compatible with the installed battlecam", failure)
            fallback
        }
    }
}
