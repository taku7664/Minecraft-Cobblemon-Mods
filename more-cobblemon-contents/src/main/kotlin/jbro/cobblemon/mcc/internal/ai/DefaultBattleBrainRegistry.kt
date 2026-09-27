package jbro.cobblemon.mcc.internal.ai

import jbro.cobblemon.mcc.api.ai.BattleBrainProvider
import jbro.cobblemon.mcc.api.ai.BattleBrainRegistrationStatus
import jbro.cobblemon.mcc.api.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.api.ai.BrainCapability
import jbro.cobblemon.mcc.api.ai.BrainId

internal class DefaultBattleBrainRegistry : BattleBrainRegistry {
    private val providers = linkedMapOf<BrainId, BattleBrainProvider>()

    @Synchronized
    override fun register(provider: BattleBrainProvider): BattleBrainRegistrationStatus {
        if (provider.id in providers) return BattleBrainRegistrationStatus.DUPLICATE_ID
        providers[provider.id] = provider
        return BattleBrainRegistrationStatus.REGISTERED
    }

    @Synchronized
    override fun find(id: BrainId, requiredCapability: BrainCapability): BattleBrainProvider? =
        providers[id]?.takeIf { requiredCapability in it.capabilities }

    @Synchronized
    override fun all(): List<BattleBrainProvider> = providers.values.toList()
}
