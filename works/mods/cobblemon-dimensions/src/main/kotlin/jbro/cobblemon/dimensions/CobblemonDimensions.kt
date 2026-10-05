package jbro.cobblemon.dimensions

import jbro.cobblemon.dimensions.portal.PortalBlocks
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/** Ultra Space and the paradox dimensions. The worlds are data; this wires up travel and the rules inside them. */
object CobblemonDimensions : ModInitializer {
    const val MOD_ID = "cobblemon_dimensions"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        DimensionRules.register()
        DimensionCommand.register()
        PortalBlocks.register()
    }

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)
}
