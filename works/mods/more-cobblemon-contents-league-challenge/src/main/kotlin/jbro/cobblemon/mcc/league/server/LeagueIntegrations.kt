package jbro.cobblemon.mcc.league.server

import dev.matthiesen.cobbled_level_control.common.CobbledLevelControl
import dev.matthiesen.cobbled_level_control.common.config.CLCConfig
import net.levelscraft7.pokebadges.api.BadgeOperationResult
import net.levelscraft7.pokebadges.api.PokeBadgesApi
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import jbro.cobblemon.mcc.league.system.LevelCapMapping
import jbro.cobblemon.mcc.league.system.LeagueCatalog

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
        // MCC builds opponents above Cobblemon's maximum up to its own limit; one above that would fail to start.
        val highest = catalog.challenges.values.flatMap { it.team }.maxOf { member ->
            Regex("""(?:^|\s)level=(\d+)""").find(member)?.groupValues?.get(1)?.toInt() ?: 0
        }
        check(highest <= jbro.cobblemon.mcc.api.battle.ManagedPveBattles.MAX_OPPONENT_LEVEL) { "opponent_level_unsupported" }
    }

    fun syncCap(player: ServerPlayer, cap: Int) {
        val leveling = CLCConfig.getLevelingConfig()
        val catching = CLCConfig.getCatchingConfig()
        check(leveling.doRestrictLeveling()) { "cap_disabled" }
        check(!catching.doNotRestrictCatching()) { "catching_cap_disabled" }
        check(!CLCConfig.SERVER_CONFIG.scaling_enableScaling.get()) { "spawn_scaling_conflict" }
        val mod = CobbledLevelControl.INSTANCE
        val store = requireNotNull(mod.storedPlayerAccountRecords) { "cap_unavailable" }
        if (!store.hasPlayerAccountRecord(player.uuid)) store.createNewPlayerAccountRecord(player.uuid)
        val current = store.getPlayerAccountRecord(player.uuid)
        val levelingTier = LevelCapMapping.nonDecreasingTier(leveling.tiers(), current.leveling, cap)
        val catchingTier = LevelCapMapping.nonDecreasingTier(catching.tiers(), current.catching, cap)
        if (current.leveling != levelingTier || current.catching != catchingTier) {
            store.editPlayerAccountRecord(player.uuid) { it.setLeveling(levelingTier); it.setCatching(catchingTier) }
            mod.sendHudSnapshot(player)
        }
        val updated = store.getPlayerAccountRecord(player.uuid)
        check(LevelCapMapping.level(leveling.tiers(), updated.leveling) >= cap &&
            LevelCapMapping.level(catching.tiers(), updated.catching) >= cap) { "cap_sync_failed" }
    }

    /** Existing CLC caps can be raised by something outside League and must remain authoritative. */
    fun currentCap(player: ServerPlayer, catching: Boolean = false): Int {
        val store = CobbledLevelControl.INSTANCE.storedPlayerAccountRecords ?: return 0
        if (!store.hasPlayerAccountRecord(player.uuid)) return 0
        val record = store.getPlayerAccountRecord(player.uuid)
        return if (catching) LevelCapMapping.level(CLCConfig.getCatchingConfig().tiers(), record.catching)
        else LevelCapMapping.level(CLCConfig.getLevelingConfig().tiers(), record.leveling)
    }

    fun awardBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.awardBadge(player, ResourceLocation.parse(badge)) in setOf(
            BadgeOperationResult.SUCCESS, BadgeOperationResult.ALREADY_OWNED,
        )

    /** Gives [player] [badge] or takes it back; for operators setting progress. */
    fun setBadge(player: ServerPlayer, badge: String, owned: Boolean) {
        val id = ResourceLocation.parse(badge)
        if (owned) PokeBadgesApi.awardBadge(player, id) else PokeBadgesApi.removeBadge(player, id)
    }

    fun hasBadge(player: ServerPlayer, badge: String): Boolean =
        PokeBadgesApi.hasBadge(player, ResourceLocation.parse(badge)) == BadgeOperationResult.SUCCESS
}
