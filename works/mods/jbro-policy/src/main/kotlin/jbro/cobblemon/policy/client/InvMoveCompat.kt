package jbro.cobblemon.policy.client

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.loader.api.FabricLoader

/**
 * InvMove lets players walk with any screen it does not know open, and hides that screen's background. Pichu's
 * screen is a menu like the pause screen: it must hold the player still and blur the world. InvMove takes per-mod
 * rules as modules; this one is built through reflection so InvMove stays optional, and says FORCE for Pichu's
 * screen and PASS for every other.
 */
object InvMoveCompat {
    private const val MODULE = "me.pieking1215.invmove.module.Module"

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("invmove")) return
        try {
            val moduleClass = Class.forName(MODULE)
            val movement = enumValue("$MODULE\$Movement")
            val background = enumValue("$MODULE\$Background")
            val config = Class.forName("me.pieking1215.invmove.module.config.ModuleConfig").getConstructor(String::class.java)
            val handler = InvocationHandler { proxy, method, args ->
                val pichu = args?.firstOrNull() is PichuScreen
                when (method.name) {
                    "getId" -> JbroPolicy.MOD_ID
                    "shouldAllowMovement" -> movement(if (pichu) "FORCE_DISABLE" else "PASS")
                    "shouldHideBackground" -> background(if (pichu) "FORCE_SHOW" else "PASS")
                    "getMovementConfig", "getBackgroundConfig" -> config.newInstance(JbroPolicy.MOD_ID)
                    "toString" -> "jbro_policy InvMove module"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> if (method.isDefault) InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray())) else null
                }
            }
            val module = Proxy.newProxyInstance(moduleClass.classLoader, arrayOf(moduleClass), handler)
            Class.forName("me.pieking1215.invmove.InvMove").getMethod("registerModule", moduleClass).invoke(null, module)
        } catch (failure: ReflectiveOperationException) {
            JbroPolicy.LOGGER.warn("Could not tell InvMove to hold still under Pichu's screen", failure)
        }
    }

    private fun enumValue(name: String): (String) -> Any {
        val type = Class.forName(name)
        val values = type.enumConstants.associateBy { (it as Enum<*>).name }
        return { values.getValue(it) }
    }
}
