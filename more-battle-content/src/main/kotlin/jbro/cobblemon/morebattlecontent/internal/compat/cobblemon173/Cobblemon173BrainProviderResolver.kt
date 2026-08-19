package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProvider
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability

internal object Cobblemon173BrainProviderResolver {
    fun create(
        registry: BattleBrainRegistry,
        capability: BrainCapability,
        role: BattleBrainProviderRole,
        selectionContext: BattleBrainSelectionContext,
    ): BattleBrain? {
        val providers = registry.all()
            .filter { provider ->
                provider.role == role && capability in provider.capabilities && isEligible(provider, selectionContext)
            }
            .sortedBy { provider -> provider.id.value }
        if (providers.size > 1) {
            MoreBattleContent.LOGGER.warn(
                "Multiple {} Battle Brain providers support {}; using {}",
                role,
                capability,
                providers.first().id,
            )
        }
        val provider = providers.firstOrNull() ?: return null
        return try {
            provider.factory.create()
        } catch (exception: RuntimeException) {
            MoreBattleContent.LOGGER.error("Battle Brain provider {} could not be created", provider.id, exception)
            null
        }
    }

    private fun isEligible(
        provider: BattleBrainProvider,
        context: BattleBrainSelectionContext,
    ): Boolean = try {
        provider.isEligible(context)
    } catch (exception: RuntimeException) {
        MoreBattleContent.LOGGER.error(
            "Battle Brain provider {} eligibility check failed for content {}",
            provider.id,
            context.contentId,
            exception,
        )
        false
    }
}
