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
        val leveling = CLCConfig.getLevelingConfig()
        val catching = CLCConfig.getCatchingConfig()
        check(leveling.doRestrictLeveling()) { "cap_disabled" }
        check(!catching.doNotRestrictCatching()) { "catching_cap_disabled" }
        check(!CLCConfig.SERVER_CONFIG.scaling_enableScaling.get()) { "spawn_scaling_conflict" }
        val required = (listOf(catalog.initialCap) + catalog.challenges.values.map { it.unlockCap }).toSet()
        LevelCapMapping.resolve(leveling.tiers(), required)
        LevelCapMapping.resolve(catching.tiers(), required)
    }

    fun syncCap(player: ServerPlayer, cap: Int) {
        val leveling = CLCConfig.getLevelingConfig()
        val catching = CLCConfig.getCatchingConfig()
        check(leveling.doRestrictLeveling()) { "cap_disabled" }
        check(!catching.doNotRestrictCatching()) { "catching_cap_disabled" }
        check(!CLCConfig.SERVER_CONFIG.scaling_enableScaling.get()) { "spawn_scaling_conflict" }
        val levelingTier = LevelCapMapping.resolve(leveling.tiers(), setOf(cap)).getValue(cap)
        val catchingTier = LevelCapMapping.resolve(catching.tiers(), setOf(cap)).getValue(cap)
        val mod = CobbledLevelControl.INSTANCE
        val store = requireNotNull(mod.storedPlayerAccountRecords) { "cap_unavailable" }
        if (!store.hasPlayerAccountRecord(player.uuid)) store.createNewPlayerAccountRecord(player.uuid)
        val current = store.getPlayerAccountRecord(player.uuid)
        if (current.leveling != levelingTier || current.catching != catchingTier) {
            store.editPlayerAccountRecord(player.uuid) { it.setLeveling(levelingTier); it.setCatching(catchingTier) }
            mod.sendHudSnapshot(player)
        }
        val updated = store.getPlayerAccountRecord(player.uuid)
        check(updated.leveling == levelingTier && updated.catching == catchingTier) { "cap_sync_failed" }
    }

    fun awardBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.awardBadge(player, ResourceLocation.parse(badge)) in setOf(
            BadgeOperationResult.SUCCESS, BadgeOperationResult.ALREADY_OWNED,
        )

    fun hasBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.hasBadge(player, ResourceLocation.parse(badge)) == BadgeOperationResult.SUCCESS
}
