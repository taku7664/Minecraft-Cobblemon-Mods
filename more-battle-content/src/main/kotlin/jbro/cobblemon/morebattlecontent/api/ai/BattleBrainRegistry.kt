package jbro.cobblemon.morebattlecontent.api.ai

import jbro.cobblemon.morebattlecontent.internal.ai.DefaultBattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds

object BattleBrainContentIds {
    const val BATTLE_TOWER: String = ManagedBattleContentIds.BATTLE_TOWER
    const val BATTLE_FACTORY: String = ManagedBattleContentIds.BATTLE_FACTORY
}

enum class BattleEncounterRole { REGULAR, BOSS }

data class BattleBrainSelectionContext(
    val contentId: String,
    val encounterRole: BattleEncounterRole,
    val difficultyTier: BattleTrainerTier,
) {
    init {
        require(isValidContentId(contentId)) { "Battle Brain content ID must be a lowercase namespaced ID" }
    }

    companion object {
        private val CONTENT_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

        @JvmStatic
        fun isValidContentId(value: String): Boolean = CONTENT_ID.matches(value)
    }
}

fun interface BattleBrainSelectionPolicy {
    fun allows(context: BattleBrainSelectionContext): Boolean

    companion object {
        @JvmField
        val ALWAYS: BattleBrainSelectionPolicy = BattleBrainSelectionPolicy { true }
    }
}

class BattleBrainProvider(
    val id: BrainId,
    capabilities: Set<BrainCapability>,
    val factory: BattleBrainFactory,
    val role: BattleBrainProviderRole = BattleBrainProviderRole.PRIMARY,
) {
    val capabilities: Set<BrainCapability> = capabilities.toSet()
    private var selectionPolicy: BattleBrainSelectionPolicy = BattleBrainSelectionPolicy.ALWAYS

    constructor(
        id: BrainId,
        capabilities: Set<BrainCapability>,
        factory: BattleBrainFactory,
        role: BattleBrainProviderRole,
        selectionPolicy: BattleBrainSelectionPolicy,
    ) : this(id, capabilities, factory, role) {
        this.selectionPolicy = selectionPolicy
    }

    init {
        require(capabilities.isNotEmpty()) { "A brain provider must declare at least one capability" }
    }

    fun isEligible(context: BattleBrainSelectionContext): Boolean = selectionPolicy.allows(context)
}

enum class BattleBrainProviderRole { PRIMARY, LOCAL }

enum class BattleBrainRegistrationStatus { REGISTERED, DUPLICATE_ID }

interface BattleBrainRegistry {
    fun register(provider: BattleBrainProvider): BattleBrainRegistrationStatus
    fun find(id: BrainId, requiredCapability: BrainCapability): BattleBrainProvider?
    fun all(): List<BattleBrainProvider>

    companion object {
        private val globalRegistry: BattleBrainRegistry = DefaultBattleBrainRegistry()

        @JvmStatic
        fun global(): BattleBrainRegistry = globalRegistry

        @JvmStatic
        fun create(): BattleBrainRegistry = DefaultBattleBrainRegistry()
    }
}
