package jbro.cobblemon.npc.client

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import jbro.cobblemon.npc.CobblemonNpc
import net.fabricmc.loader.api.FabricLoader

/** Optional InvMove integration: authoring screens hold the player still and retain their background. */
object NpcEditorInvMoveCompat {
    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("invmove")) return
        try {
            val name = "me.pieking1215.invmove.module.Module"
            val type = Class.forName(name)
            val movement = Class.forName("$name\$Movement").enumConstants.associateBy { (it as Enum<*>).name }
            val background = Class.forName("$name\$Background").enumConstants.associateBy { (it as Enum<*>).name }
            val config = Class.forName("me.pieking1215.invmove.module.config.ModuleConfig").getConstructor(String::class.java)
            val handler = InvocationHandler { proxy, method, args ->
                val editor = args?.firstOrNull() is NpcEditorScreen
                when (method.name) {
                    "getId" -> CobblemonNpc.MOD_ID
                    "shouldAllowMovement" -> movement.getValue(if (editor) "FORCE_DISABLE" else "PASS")
                    "shouldHideBackground" -> background.getValue(if (editor) "FORCE_SHOW" else "PASS")
                    "getMovementConfig", "getBackgroundConfig" -> config.newInstance(CobblemonNpc.MOD_ID)
                    "toString" -> "cobblemon_npc InvMove module"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> if (method.isDefault) InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray())) else null
                }
            }
            val module = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
            Class.forName("me.pieking1215.invmove.InvMove").getMethod("registerModule", type).invoke(null, module)
        } catch (failure: ReflectiveOperationException) {
            CobblemonNpc.LOGGER.warn("Could not register NPC editor InvMove compatibility", failure)
        }
    }
}
