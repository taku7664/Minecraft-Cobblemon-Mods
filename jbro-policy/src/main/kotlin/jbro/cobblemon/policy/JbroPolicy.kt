package jbro.cobblemon.policy

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

/** Polish for jbro's own server. Each feature lives in its own package; this only names the mod. */
object JbroPolicy : ModInitializer {
    const val MOD_ID = "jbro_policy"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {}
}
