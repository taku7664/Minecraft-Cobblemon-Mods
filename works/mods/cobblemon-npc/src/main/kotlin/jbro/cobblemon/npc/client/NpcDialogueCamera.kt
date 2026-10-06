package jbro.cobblemon.npc.client

import jbro.cobblemon.battlecam.api.BattlecamScenes
import jbro.cobblemon.npc.CobblemonNpc
import net.fabricmc.loader.api.FabricLoader

/**
 * Turns Better Cobblemon Battlecam to the NPC a dialogue box belongs to, when that mod is installed (it is optional;
 * its classes are only touched then). The box lets go when it closes; the time limit only bounds a box never closed.
 */
internal object NpcDialogueCamera {
    private const val LIMIT_MILLIS = 300_000L
    private val installed = FabricLoader.getInstance().isModLoaded("better_cobblemon_battlecam")
    private var focused = false

    fun focus(entityId: Int) {
        if (!installed) return
        focused = guarded(false) { BattlecamScenes.focus(entityId, LIMIT_MILLIS) }
    }

    fun release() {
        if (!installed || !focused) return
        focused = false
        guarded(Unit) { BattlecamScenes.release() }
    }

    private inline fun <T> guarded(fallback: T, action: () -> T): T = try {
        action()
    } catch (failure: RuntimeException) {
        CobblemonNpc.LOGGER.warn("NPC dialogue camera failed", failure)
        fallback
    } catch (failure: LinkageError) {
        CobblemonNpc.LOGGER.warn("NPC dialogue camera is not compatible with the installed battlecam", failure)
        fallback
    }
}
