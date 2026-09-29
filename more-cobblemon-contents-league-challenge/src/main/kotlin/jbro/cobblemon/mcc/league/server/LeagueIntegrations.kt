package jbro.cobblemon.mcc.league.server

import com.cobblemon.mod.common.Cobblemon
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
        // Cobblemon clamps levels to its own maximum, so a hard opponent above it would silently lose levels.
        val highest = catalog.challenges.values.flatMap { it.team }.maxOf { member ->
            Regex("""(?:^|\s)level=(\d+)""").find(member)?.groupValues?.get(1)?.toInt() ?: 0
        }
        check(highest <= Cobblemon.config.maxPokemonLevel) { "opponent_level_unsupported" }
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
