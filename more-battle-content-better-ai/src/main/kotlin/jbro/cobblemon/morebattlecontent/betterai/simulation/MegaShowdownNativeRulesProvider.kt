package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.nio.file.Path

internal data class NativeRuleRegistryDescriptor(
    val registry: NativeRuleRegistry,
    val className: String,
    val getterName: String,
) {
    init {
        require(className.isNotBlank() && getterName.isNotBlank())
    }
}

internal class ReflectiveNativeRulesProvider(
    private val descriptors: List<NativeRuleRegistryDescriptor>,
) {
    init {
        require(descriptors.map(NativeRuleRegistryDescriptor::registry).distinct().size == descriptors.size) {
            "Native rules provider cannot define a registry twice"
        }
    }

    fun capture(engineRoot: Path, classLoader: ClassLoader): NativeRulesGeneration =
        NativeRulesGeneration.capture(engineRoot, readSources(classLoader))

    internal fun readSources(classLoader: ClassLoader): List<NativeRuleSource> = descriptors.flatMap { descriptor ->
        val type = try {
            Class.forName(descriptor.className, true, classLoader)
        } catch (failure: ReflectiveOperationException) {
            throw NativeRulesUnavailableException("Missing runtime registry ${descriptor.className}", failure)
        }
        val instance = try {
            type.getField("INSTANCE").get(null)
        } catch (failure: ReflectiveOperationException) {
            throw NativeRulesUnavailableException("Runtime registry has no INSTANCE: ${descriptor.className}", failure)
        }
        val raw = try {
            type.getMethod(descriptor.getterName).invoke(instance)
        } catch (failure: ReflectiveOperationException) {
            throw NativeRulesUnavailableException(
                "Runtime registry getter is unavailable: ${descriptor.className}.${descriptor.getterName}",
                failure,
            )
        }
        val entries = raw as? Map<*, *> ?: throw NativeRulesUnavailableException(
            "Runtime registry getter did not return a map: ${descriptor.className}.${descriptor.getterName}",
        )
        entries.entries.map { (rawId, rawSource) ->
            val id = rawId as? String ?: throw NativeRulesUnavailableException(
                "Runtime registry contains a non-string ID: ${descriptor.className}",
            )
            val source = rawSource as? String ?: throw NativeRulesUnavailableException(
                "Runtime registry contains a non-string source: ${descriptor.className}:$id",
            )
            NativeRuleSource(descriptor.registry, id, source)
        }
    }
}

/** Captures every JavaScript source registry injected by the required Mega Showdown runtime. */
internal object MegaShowdownNativeRulesProvider {
    internal val descriptors = listOf(
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.ABILITY,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.Abilities",
            "getAbilityScripts",
        ),
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.MOVE,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.Moves",
            "getMoveScripts",
        ),
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.SCRIPT,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.Scripts",
            "getScripts",
        ),
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.CONDITION,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.Conditions",
            "getConditionScripts",
        ),
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.HELD_ITEM,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.HeldItems",
            "getHeldItemsScripts",
        ),
        NativeRuleRegistryDescriptor(
            NativeRuleRegistry.TYPE_CHART,
            "com.github.yajatkaul.mega_showdown.fabric.datapack.showdown.TypeCharts",
            "getTypeChartScripts",
        ),
    )
    private val delegate = ReflectiveNativeRulesProvider(
        descriptors,
    )

    fun capture(engineRoot: Path, classLoader: ClassLoader = javaClass.classLoader): NativeRulesGeneration =
        delegate.capture(engineRoot, classLoader)
}

internal class NativeRulesUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
