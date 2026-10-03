package jbro.cobblemon.policy

import jbro.cobblemon.policy.api.Announcements
import jbro.cobblemon.policy.api.Tips
import jbro.cobblemon.policy.config.PolicyConfig
import jbro.cobblemon.policy.legend.LegendCommand
import jbro.cobblemon.policy.legend.LegendPolicy
import jbro.cobblemon.policy.legend.SpawnForCommand
import jbro.cobblemon.policy.plaza.Plaza
import jbro.cobblemon.policy.pokemon.PartyRelease
import jbro.cobblemon.policy.pokemon.PokemonItemRestore
import jbro.cobblemon.policy.pokemon.PokenavCommand
import jbro.cobblemon.policy.support.DiscordBot
import jbro.cobblemon.policy.support.DiscordSettings
import jbro.cobblemon.policy.support.Inquiries
import jbro.cobblemon.policy.support.InquiryReview
import jbro.cobblemon.policy.support.InquiryReviewSettings
import jbro.cobblemon.policy.welcome.WelcomeKit
import jbro.cobblemon.policy.wild.WildPokemonPolicy
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.ResourcePackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/** Policy and polish for jbro's own server. Each feature lives in its own package; this only wires them up. */
object JbroPolicy : ModInitializer {
    const val MOD_ID = "jbro_policy"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    lateinit var config: PolicyConfig
        private set

    override fun onInitialize() {
        config = PolicyConfig.load(FabricLoader.getInstance().configDir.resolve("jbro-policy.json")) { message, failure ->
            LOGGER.warn(message, failure)
        }
        Plaza.register()
        WildPokemonPolicy.register(config)
        WelcomeKit.register()
        PartyRelease.register()
        PokenavCommand.register()
        PokemonItemRestore.register()
        jbro.cobblemon.policy.support.PendingItems.register()
        jbro.cobblemon.policy.api.OperatorWhisper.register()
        LegendPolicy.register()
        LegendCommand.register()
        SpawnForCommand.register()
        // More Cobblemon Contents serves the server wiki: /wiki hands out links, and the Legend guide reads each
        // player's progress.
        if (FabricLoader.getInstance().isModLoaded("more_cobblemon_contents")) {
            jbro.cobblemon.policy.wiki.WikiCommand.register()
            jbro.cobblemon.policy.legend.LegendWikiSection.register()
            jbro.cobblemon.policy.support.InquiryWikiEndpoint.register()
        }
        Announcements.register()
        Tips.register(config.tipIntervalSeconds, config.tips)
        val configDir = FabricLoader.getInstance().configDir
        val discord = DiscordSettings.load(configDir.resolve("jbro-policy-discord.json")) { message, failure -> LOGGER.warn(message, failure) }
        Inquiries.register(discord)
        InquiryReview.register(discord,
            InquiryReviewSettings.load(configDir.resolve("jbro-policy-inquiry-review.json")) { message, failure -> LOGGER.warn(message, failure) },
            FabricLoader.getInstance().gameDir, withContents = FabricLoader.getInstance().isModLoaded("more_cobblemon_contents"))
        DiscordBot.register(discord, configDir.resolve("jbro-policy-discord-status.json"),
            withContents = FabricLoader.getInstance().isModLoaded("more_cobblemon_contents"))
        // Built-in data packs, so either can be turned off per world with /datapack disable.
        val mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow()
        // Keep the legacy candy pack ID so existing worlds retain their activation setting.
        // Its resources now block experience candy crafting, not stat candy crafting.
        for (pack in listOf("legendary_spawns", "no_stat_candy_l_xl", "no_ability_patch")) {
            ResourceManagerHelper.registerBuiltinResourcePack(id(pack), mod,
                Component.translatable("pack.$MOD_ID.$pack"), ResourcePackActivationType.DEFAULT_ENABLED)
        }
    }

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)
}
