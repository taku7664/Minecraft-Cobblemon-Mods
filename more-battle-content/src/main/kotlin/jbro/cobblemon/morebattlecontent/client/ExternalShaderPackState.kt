package jbro.cobblemon.morebattlecontent.client

import java.lang.reflect.Method
import net.fabricmc.loader.api.FabricLoader

/** Read-only optional Iris bridge. MBC must never toggle or rebuild the user's shader pack. */
internal object ExternalShaderPackState {
    private var resolutionAttempted = false
    private var bindings: Bindings? = null

    fun isInUse(): Boolean {
        if (!FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID)) return false
        val active = resolve() ?: return false
        return runCatching { active.isShaderPackInUse.invoke(active.api) as Boolean }.getOrDefault(false)
    }

    fun isRenderingShadowPass(): Boolean {
        if (!FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID)) return false
        val active = resolve() ?: return false
        return runCatching { active.isRenderingShadowPass.invoke(active.api) as Boolean }.getOrDefault(false)
    }

    private fun resolve(): Bindings? {
        bindings?.let { return it }
        if (resolutionAttempted) return null
        resolutionAttempted = true
        return runCatching {
            val apiClass = Class.forName(IRIS_API_CLASS)
            val api = apiClass.getMethod("getInstance").invoke(null)
            Bindings(
                api = api,
                isShaderPackInUse = apiClass.getMethod("isShaderPackInUse"),
                isRenderingShadowPass = apiClass.getMethod("isRenderingShadowPass"),
            )
        }.getOrNull()?.also { bindings = it }
    }

    private data class Bindings(
        val api: Any,
        val isShaderPackInUse: Method,
        val isRenderingShadowPass: Method,
    )

    private const val IRIS_MOD_ID = "iris"
    private const val IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi"
}
