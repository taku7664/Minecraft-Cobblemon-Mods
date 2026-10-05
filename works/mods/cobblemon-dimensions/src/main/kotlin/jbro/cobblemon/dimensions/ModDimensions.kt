package jbro.cobblemon.dimensions

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

/** The dimensions this mod adds. Their worlds are data: `data/cobblemon_dimensions/dimension/<name>.json`. */
enum class ModDimension(val path: String) {
    ULTRA_SPACE("ultra_space"),
    ANCIENT("ancient"),
    FUTURE("future");

    val key: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, CobblemonDimensions.id(path))

    companion object {
        fun of(level: Level): ModDimension? = entries.firstOrNull { it.key == level.dimension() }
        fun byPath(path: String): ModDimension? = entries.firstOrNull { it.path == path }
    }
}

object ModDimensions {
    /**
     * Hub dimensions a player can visit from inside these dimensions and come back from: the plaza and player rooms.
     * They keep their own return points, so entering one of them never counts as leaving.
     */
    val HUBS: Set<ResourceLocation> = setOf(
        ResourceLocation.fromNamespaceAndPath("jbro_policy", "plaza"),
        ResourceLocation.fromNamespaceAndPath("myroom", "rooms"),
    )

    fun isOurs(level: Level): Boolean = ModDimension.of(level) != null
    fun isHub(level: Level): Boolean = level.dimension().location() in HUBS
}
