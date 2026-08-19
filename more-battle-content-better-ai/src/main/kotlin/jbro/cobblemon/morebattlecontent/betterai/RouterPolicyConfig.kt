package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

internal enum class RouterActivationMode {
    LOCAL_ONLY,
    BOSS_ONLY,
    ALL,
    DIFFICULTY_TIERS,
}

internal class RouterContentRule(
    val mode: RouterActivationMode,
    tiers: Set<BattleTrainerTier> = emptySet(),
) {
    val tiers: Set<BattleTrainerTier> = tiers.toSet()

    init {
        require((mode == RouterActivationMode.DIFFICULTY_TIERS) == this.tiers.isNotEmpty()) {
            "Router difficulty-tier mode requires tiers, and other modes must not declare them"
        }
    }

    fun allows(context: BattleBrainSelectionContext): Boolean = when (mode) {
        RouterActivationMode.LOCAL_ONLY -> false
        RouterActivationMode.BOSS_ONLY -> context.encounterRole == BattleEncounterRole.BOSS
        RouterActivationMode.ALL -> true
        RouterActivationMode.DIFFICULTY_TIERS -> context.difficultyTier in tiers
    }
}

internal class RouterPolicyConfig(
    val defaultMode: RouterActivationMode,
    contentRules: Map<String, RouterContentRule>,
) {
    val contentRules: Map<String, RouterContentRule> = LinkedHashMap(contentRules)

    init {
        require(defaultMode != RouterActivationMode.DIFFICULTY_TIERS) {
            "Default Router mode cannot require a content-specific tier list"
        }
        require(this.contentRules.keys.all(BattleBrainSelectionContext::isValidContentId)) {
            "Router content rules require lowercase namespaced content IDs"
        }
    }

    fun allows(context: BattleBrainSelectionContext): Boolean =
        (contentRules[context.contentId] ?: RouterContentRule(defaultMode)).allows(context)

    companion object {
        fun bossOnlyMbcPve(): RouterPolicyConfig = RouterPolicyConfig(
            defaultMode = RouterActivationMode.LOCAL_ONLY,
            contentRules = linkedMapOf(
                BattleBrainContentIds.BATTLE_TOWER to RouterContentRule(RouterActivationMode.BOSS_ONLY),
                BattleBrainContentIds.BATTLE_FACTORY to RouterContentRule(RouterActivationMode.BOSS_ONLY),
            ),
        )

        fun legacyAll(): RouterPolicyConfig = RouterPolicyConfig(
            defaultMode = RouterActivationMode.ALL,
            contentRules = emptyMap(),
        )
    }
}
