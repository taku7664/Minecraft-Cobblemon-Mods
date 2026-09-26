package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import dev.matthiesen.cobbled_level_control.common.CobbledLevelControl
import dev.matthiesen.cobbled_level_control.common.config.CLCConfig
import net.levelscraft7.pokebadges.api.BadgeOperationResult
import net.levelscraft7.pokebadges.api.PokeBadgesApi
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.LevelCapMapping
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.LeagueCatalog

/** Version-pinned typed adapters; no command execution or reflective API guessing. */
object LeagueIntegrations {
    fun validateCaps(catalog: LeagueCatalog) {
        val config = CLCConfig.getLevelingConfig()
        check(config.doRestrictLeveling()) { "cap_disabled" }
        LevelCapMapping.resolve(config.tiers(), (listOf(catalog.initialCap) + catalog.challenges.values.map { it.unlockCap }).toSet())
    }

    fun syncCap(player: ServerPlayer, cap: Int) {
        val config = CLCConfig.getLevelingConfig()
        check(config.doRestrictLeveling()) { "cap_disabled" }
        val tier = LevelCapMapping.resolve(config.tiers(), setOf(cap)).getValue(cap)
        val mod = CobbledLevelControl.INSTANCE
        val store = requireNotNull(mod.storedPlayerAccountRecords) { "cap_unavailable" }
        if (!store.hasPlayerAccountRecord(player.uuid)) store.createNewPlayerAccountRecord(player.uuid)
        if (store.getPlayerAccountRecord(player.uuid).leveling != tier) {
            store.editPlayerAccountRecord(player.uuid) { it.setLeveling(tier) }
            mod.sendHudSnapshot(player)
        }
        check(store.getPlayerAccountRecord(player.uuid).leveling == tier) { "cap_sync_failed" }
    }

    fun awardBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.awardBadge(player, ResourceLocation.parse(badge)) in setOf(
            BadgeOperationResult.SUCCESS, BadgeOperationResult.ALREADY_OWNED,
        )

    fun hasBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.hasBadge(player, ResourceLocation.parse(badge)) == BadgeOperationResult.SUCCESS
}
