package kr.parkjh.pokefusion

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

object PendingOutputService {
    fun prepare(player: ServerPlayer): PokeFusionPlayerState? {
        val loaded = PokeFusionPlayerStorage.load(player)
        if (loaded !is PokeFusionPlayerStorage.LoadResult.Success) return null
        var state = loaded.state
        if (!state.legacyMigrationComplete) {
            val legacy = PendingOutputSavedData.get(player.server)
            if (legacy.isAvailable) {
                state = state.importLegacy(legacy.snapshot(player.uuid))
                PokeFusionPlayerStorage.store(player, state)
            }
        }
        return state
    }

    fun storeInputs(player: ServerPlayer, base: ItemStack, materials: List<ItemStack>): Boolean {
        val state = prepare(player) ?: return false
        PokeFusionPlayerStorage.store(player, state.withInputs(base, materials))
        return true
    }

    fun completeFusion(player: ServerPlayer, outputs: List<ItemStack>): Boolean {
        val state = prepare(player) ?: return false
        PokeFusionPlayerStorage.store(player, state.completeFusion(outputs))
        return true
    }

    fun returnInputs(player: ServerPlayer): Boolean {
        val state = prepare(player) ?: return false
        PokeFusionPlayerStorage.store(player, state.moveInputsToPending())
        return true
    }

    fun count(player: ServerPlayer): Int = prepare(player)?.pendingOutputs?.size ?: 0

    fun deliver(player: ServerPlayer): Int {
        val state = prepare(player) ?: return 0
        if (state.pendingOutputs.isEmpty()) return 0
        val remaining = mutableListOf<ItemStack>()
        var delivered = 0
        for (original in state.pendingOutputs) {
            val candidate = original.copy()
            player.inventory.add(candidate)
            if (candidate.isEmpty || player.drop(candidate, false) != null) {
                delivered++
            } else {
                remaining += candidate
            }
        }
        PokeFusionPlayerStorage.store(player, state.withPending(remaining))
        return delivered
    }

    fun recoverAfterJoin(player: ServerPlayer): Int {
        val state = prepare(player) ?: return 0
        if (!state.baseInput.isEmpty || state.materialInputs.isNotEmpty()) {
            PokeFusionPlayerStorage.store(player, state.moveInputsToPending())
        }
        return deliver(player)
    }
}
