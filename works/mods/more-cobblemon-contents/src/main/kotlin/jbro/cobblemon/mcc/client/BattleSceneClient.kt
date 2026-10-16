package jbro.cobblemon.mcc.client

import jbro.cobblemon.battlecam.api.BattlecamScenes
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.presentation.BattleScenePayload
import jbro.cobblemon.mcc.internal.presentation.BattleSceneAwaitPayload
import jbro.cobblemon.mcc.internal.presentation.BattleSceneCancelPayload
import jbro.cobblemon.mcc.internal.presentation.BattleSceneFinishedPayload
import jbro.cobblemon.ui.extended.BattleDialogue
import jbro.cobblemon.ui.extended.SceneDialogue
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.Util
import net.minecraft.network.chat.Component

internal class BattleScenePlaybackState {
    class Token internal constructor(val completionId: java.util.UUID?)

    private var current: Token? = null
    val playing: Boolean get() = current != null
    val completionId: java.util.UUID? get() = current?.completionId

    fun begin(completionId: java.util.UUID?): Token = Token(completionId).also { current = it }

    fun finish(token: Token): Boolean {
        if (current !== token) return false
        current = null
        return true
    }
}

/**
 * Plays the server's battle scenes: the lines in Cobblemon UI's message box and, with Better Cobblemon Battlecam
 * installed, the camera on the speaker until the last line is gone. The server ends a battle before it names the
 * winner, so a battle's end is held for a moment in case its closing scene follows, and then until that scene is over:
 * the battle closes after its last words, not before them.
 */
object BattleSceneClient {
    /** How long a battle's end waits for a closing scene that may follow it. */
    private const val SCENE_GRACE_MILLIS = 500L
    /** The camera is let go when the lines end; this only bounds it if that never comes. */
    private const val CAMERA_LIMIT_MILLIS = 120_000L

    private data class ClientScene(
        val focusEntityId: Int,
        val speaker: Component?,
        val lines: List<Component>,
        val interject: Boolean,
        val completionId: java.util.UUID?,
    )

    private var pending: ClientScene? = null
    private val playback = BattleScenePlaybackState()
    private var heldEnd: Runnable? = null
    private var heldSince = 0L
    private var replaying = false

    internal fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleScenePayload.TYPE) { payload, context ->
            context.client().execute {
                pending?.completionId?.let(::acknowledge)
                pending = ClientScene(payload.focusEntityId, payload.speaker, payload.lines, payload.interject, null)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleSceneAwaitPayload.TYPE) { payload, context ->
            context.client().execute {
                pending?.completionId?.let(::acknowledge)
                pending = ClientScene(payload.focusEntityId, payload.speaker, payload.lines, false, payload.completionId)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleSceneCancelPayload.TYPE) { payload, context ->
            context.client().execute {
                if (pending?.completionId == payload.completionId) pending = null
                if (playback.completionId == payload.completionId) SceneDialogue.stop()
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { _ -> tick() }
        MccClientSessionReset.onReset("battle scene") {
            pending?.completionId?.let(::acknowledge)
            pending = null
            // A battle that teleports its players out (a PvP lounge) changes world inside the grace wait: dropping the
            // held end there left Cobblemon's client battle, and its music, running for good.
            releaseEnd()
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

    /** A new battle is opening: the last one's held end finishes now, before it could close the new one. */
    @JvmStatic
    fun finishHeldEnd() {
        if (replaying) return
        releaseEnd()
    }

    private fun tick() {
        val now = Util.getMillis()
        if (heldEnd != null && pending == null && !playback.playing && now - heldSince > SCENE_GRACE_MILLIS) {
            releaseEnd()
        }
        val scene = pending ?: return
        // Words said mid-turn (a Dynamax) come in at once; closing words wait until the player clears narration.
        if (!scene.interject && BattleDialogue.hasPending()) return
        pending = null
        play(scene)
    }

    private fun play(scene: ClientScene) {
        // SceneDialogue.play() stops the previous scene synchronously. Let that callback finish while it still owns
        // the playback state, then install this scene's token so a stale callback can never clear the new scene.
        SceneDialogue.stop()
        val token = playback.begin(scene.completionId)
        var focused = false
        SceneDialogue.play(scene.speaker, scene.lines, onFinish@{
            if (!playback.finish(token)) return@onFinish
            if (focused) Battlecam.release()
            scene.completionId?.let(::acknowledge)
            releaseEnd()
        })
        if (scene.focusEntityId != BattleScenePayload.NO_FOCUS && SceneDialogue.isActive()) {
            focused = Battlecam.focus(scene.focusEntityId, CAMERA_LIMIT_MILLIS)
        }
    }

    private fun acknowledge(id: java.util.UUID) {
        if (ClientPlayNetworking.canSend(BattleSceneFinishedPayload.TYPE)) {
            ClientPlayNetworking.send(BattleSceneFinishedPayload(id))
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
