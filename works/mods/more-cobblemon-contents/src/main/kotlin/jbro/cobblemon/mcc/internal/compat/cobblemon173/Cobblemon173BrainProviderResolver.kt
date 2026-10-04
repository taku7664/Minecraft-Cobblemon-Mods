package jbro.cobblemon.mcc.internal.compat.cobblemon173

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleBrain
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainProvider
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BrainCapability

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
            MoreCobblemonContents.LOGGER.warn(
                "Multiple {} Battle Brain providers support {}; using {}",
                role,
                capability,
                providers.first().id,
            )
        }
        val provider = providers.firstOrNull() ?: return null
        return compatibilityCallOrElse(
            fallback = { failure ->
                reportManagedCleanupFailureSafely(failure) {
                    MoreCobblemonContents.LOGGER.error("Battle Brain provider {} could not be created", provider.id, it)
                }
                null
            },
            action = provider.factory::create,
        )
    }

    private fun isEligible(
        provider: BattleBrainProvider,
        context: BattleBrainSelectionContext,
    ): Boolean = compatibilityCallOrElse(
        fallback = { failure ->
            reportManagedCleanupFailureSafely(failure) {
                MoreCobblemonContents.LOGGER.error(
                    "Battle Brain provider {} eligibility check failed for content {}",
                    provider.id,
                    context.contentId,
                    it,
                )
            }
            false
        },
        action = { provider.isEligible(context) },
    )
}
