package jbro.cobblemon.mcc.pwt

import jbro.cobblemon.mcc.pwt.server.PwtCatalogResources
import jbro.cobblemon.mcc.pwt.server.PwtServer
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object MoreCobblemonContentsPwt : ModInitializer {
    const val MOD_ID: String = "more_cobblemon_contents_pwt"
    /** The hub tab and battle content ID. */
    const val CONTENT: String = "more_cobblemon_contents:pwt"

    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        PwtCatalogResources.register()
        PwtServer.register()
    }
}
