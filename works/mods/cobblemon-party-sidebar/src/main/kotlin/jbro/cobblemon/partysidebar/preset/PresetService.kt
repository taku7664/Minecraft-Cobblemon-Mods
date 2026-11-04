package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.PresetActionPayload
import jbro.cobblemon.partysidebar.network.PresetStatePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Party presets and the item storage behind them.
 *
 * The active preset is the live party: every few ticks it takes down which Pokemon stands in each row and which
 * storage item each one borrows. Switching to another preset sends the Pokemon it does not use to the PC, brings its
 * own from the PC, returns every borrowed item to the storage and lends the new preset's items out again. Switching
 * happens only outside battle, so a battle never has to be unwound.
 *
 * A row whose preset lends an item the Pokemon is not holding (a battle used it up, or the storage had none left) is
 * reported as missing, and the sidebar shows that row in red.
 */
object PresetService {
    private const val TICK_INTERVAL = 10
    private val lastSent = HashMap<UUID, PresetStatePayload>()
    private var ticks = 0

    fun data(player: ServerPlayer): PlayerPresets {
        val data = PresetData.of(player.server).get(player.uuid)
        if (data.presets.isEmpty()) {
            data.presets += snapshot(player)
            data.active = 0
            dirty(player)
        }
        return data
    }

    fun storage(player: ServerPlayer): StorageContainer = StorageContainer(data(player).storage) { dirty(player) }

    fun handle(player: ServerPlayer, payload: PresetActionPayload) {
        when (payload.action) {
            PresetActionPayload.Action.SELECT -> select(player, payload.index)
            PresetActionPayload.Action.ADD -> add(player)
            PresetActionPayload.Action.DELETE -> delete(player)
            PresetActionPayload.Action.OPEN_STORAGE -> openStorage(player)
        }
        sync(player, force = true)
    }

    fun tick(server: MinecraftServer) {
        if (++ticks % TICK_INTERVAL != 0) return
        for (player in server.playerList.players) {
            try {
                if (player.containerMenu !is StorageMenu) returnLeakedItems(player)
                if (mirror(player, data(player))) dirty(player)
                sync(player, force = false)
            } catch (failure: Exception) {
                CobblemonPartySidebar.LOGGER.error("Preset update failed for {}", player.scoreboardName, failure)
            }
        }
    }

    fun forget(player: ServerPlayer) {
        lastSent.remove(player.uuid)
    }

    fun sync(player: ServerPlayer, force: Boolean) {
        if (!ServerPlayNetworking.canSend(player, PresetStatePayload.TYPE)) return
        val state = state(player, data(player))
        if (!force && lastSent[player.uuid] == state) return
        lastSent[player.uuid] = state
        ServerPlayNetworking.send(player, state)
    }

    /** The player took a borrowed item off [pokemon] themselves: its preset stops lending one. */
    fun forgetReference(player: ServerPlayer, pokemon: Pokemon) {
        data(player).activePreset().slots.filter { it.pokemon == pokemon.uuid }.forEach { it.reference = ItemStack.EMPTY }
        dirty(player)
    }

    private fun select(player: ServerPlayer, index: Int) {
        apply(player, data(player), index)
    }

    /** A new preset starts as a copy of the current one, so nothing moves. */
    private fun add(player: ServerPlayer) {
        val data = data(player)
        if (data.presets.size >= PresetData.MAX_PRESETS) {
            reject(player, "preset_limit")
            return
        }
        mirror(player, data)
        data.presets += Preset(data.activePreset().slots.map { PresetSlot(it.pokemon, it.reference.copy()) })
        data.active = data.presets.lastIndex
        dirty(player)
    }

    /** Deleting the current preset switches to its neighbour first; the last preset cannot be deleted. */
    private fun delete(player: ServerPlayer) {
        val data = data(player)
        if (data.presets.size <= 1) {
            reject(player, "preset_last")
            return
        }
        val old = data.active
        if (!apply(player, data, if (old < data.presets.lastIndex) old + 1 else old - 1)) return
        data.presets.removeAt(old)
        if (data.active > old) data.active--
        dirty(player)
    }

    private fun openStorage(player: ServerPlayer) {
        if (!player.containerMenu.carried.isEmpty || !player.inventoryMenu.carried.isEmpty) return
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> StorageMenu(id, inventory, storage(player)) },
            Component.translatable("container.cobblemon_party_sidebar.item_storage")))
    }

    private fun apply(player: ServerPlayer, data: PlayerPresets, index: Int): Boolean {
        if (index !in data.presets.indices) return false
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) return reject(player, "preset_in_battle")
        mirror(player, data)
        if (index == data.active) return true
        val party = Cobblemon.storage.getParty(player)
        val pc = Cobblemon.storage.getPC(player)
        val target = data.presets[index]
        val current = (0 until PresetData.PARTY_SIZE).mapNotNull { party.get(it) }
        val claimed = HashSet<UUID>()
        val resolved = target.slots.map { slot ->
            slot.pokemon?.takeIf(claimed::add)?.let { id -> current.firstOrNull { it.uuid == id } ?: pc.firstOrNull { it.uuid == id } }
        }
        val arriving = resolved.filterNotNull().filter { pokemon -> current.none { it.uuid == pokemon.uuid } }
        val leaving = current.filter { pokemon -> resolved.none { it?.uuid == pokemon.uuid } }
        if ((current + arriving).any { it.entity?.isBusy == true }) return reject(player, "busy")
        if (leaving.size > pc.boxes.sumOf { it.unoccupiedSlots } + arriving.size) return reject(player, "pc_full")
        val storage = storage(player)
        if (!storage.canTake(current.map { it.heldItem() }.filter(StoredMark::isStored))) return reject(player, "storage_full")
        return try {
            for (pokemon in current) {
                if (!StoredMark.isStored(pokemon.heldItem())) continue
                val back = pokemon.removeHeldItem()
                if (!storage.add(back)) pokemon.swapHeldItem(back, false, false)
            }
            (current + arriving).forEach { if (it.entity != null) it.recall() }
            current.forEach { party.remove(it) }
            arriving.forEach { pc.remove(it) }
            for (pokemon in leaving) if (!pc.add(pokemon)) keepSomewhere(player, pokemon)
            resolved.forEachIndexed { slot, pokemon -> if (pokemon != null) party.set(PartyPosition(slot), pokemon) }
            resolved.forEachIndexed { slot, pokemon ->
                val reference = target.slots[slot].reference
                if (pokemon == null || reference.isEmpty || !pokemon.heldItem().isEmpty) return@forEachIndexed
                val item = storage.takeOne(reference)
                if (item.isEmpty) return@forEachIndexed
                pokemon.swapHeldItem(item, false, false)
                if (pokemon.heldItem().isEmpty) storage.add(item)
            }
            data.active = index
            dirty(player)
            CobblemonPartySidebar.LOGGER.info("[party-preset] player={} preset={} arriving={} leaving={}",
                player.scoreboardName, index + 1, arriving.map { it.uuid }, leaving.map { it.uuid })
            true
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.error("Preset switch failed for {}", player.scoreboardName, failure)
            reject(player, "failed")
        }
    }

    /** The PC was checked for room, so this only runs if another mod refused the Pokemon; it must not be lost. */
    private fun keepSomewhere(player: ServerPlayer, pokemon: Pokemon) {
        val party = Cobblemon.storage.getParty(player)
        if (party.add(pokemon)) return
        CobblemonPartySidebar.LOGGER.error("[party-preset] player={} pokemon={} could not be stored after a switch",
            player.scoreboardName, pokemon.uuid)
        Cobblemon.storage.getPC(player).backupStore.add(pokemon)
    }

    /** Brings the active preset up to date with the party; true when it changed. */
    fun mirror(player: ServerPlayer, data: PlayerPresets): Boolean {
        val party = Cobblemon.storage.getParty(player)
        var changed = false
        for ((row, slot) in data.activePreset().slots.withIndex()) {
            val pokemon = party.get(row)
            if (pokemon == null) {
                val id = slot.pokemon ?: continue
                // A Pokemon moved elsewhere frees the row; one that left the party and PC (itemized, traded) keeps it.
                if (party.any { it.uuid == id } || Cobblemon.storage.getPC(player).any { it.uuid == id }) {
                    slot.pokemon = null
                    slot.reference = ItemStack.EMPTY
                    changed = true
                }
                continue
            }
            if (slot.pokemon != pokemon.uuid) {
                slot.pokemon = pokemon.uuid
                slot.reference = ItemStack.EMPTY
                changed = true
            }
            val held = pokemon.heldItem()
            if (StoredMark.isStored(held)) {
                if (!StoredMark.sameItem(slot.reference, held)) {
                    slot.reference = StoredMark.prototype(held)
                    changed = true
                }
            } else if (!held.isEmpty && !slot.reference.isEmpty) {
                slot.reference = ItemStack.EMPTY
                changed = true
            }
            // Holding nothing keeps the reference: the item was used up or taken, and the row shows it missing.
        }
        return changed
    }

    private fun state(player: ServerPlayer, data: PlayerPresets): PresetStatePayload {
        val party = Cobblemon.storage.getParty(player)
        val missing = data.activePreset().slots.mapIndexed { row, slot ->
            val pokemon = party.get(row)
            val held = pokemon?.heldItem() ?: ItemStack.EMPTY
            val satisfied = StoredMark.isStored(held) && StoredMark.sameItem(held, slot.reference)
            if (slot.reference.isEmpty || pokemon == null || pokemon.uuid != slot.pokemon || satisfied) ItemStack.EMPTY
            else slot.reference.copy()
        }
        return PresetStatePayload(data.active, data.presets.size, missing)
    }

    /**
     * A storage item that reached the player inventory outside the storage window left a Pokemon some other way
     * (another mod's screen, fusion returning held items). It goes back to the storage, or loses its mark when full.
     */
    private fun returnLeakedItems(player: ServerPlayer) {
        val inventory = player.inventory
        var storage: StorageContainer? = null
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!StoredMark.isStored(stack)) continue
            val target = storage ?: storage(player).also { storage = it }
            if (target.add(stack)) inventory.setItem(slot, ItemStack.EMPTY) else StoredMark.unmark(stack)
            inventory.setChanged()
        }
    }

    private fun snapshot(player: ServerPlayer): Preset {
        val party = Cobblemon.storage.getParty(player)
        return Preset(List(PresetData.PARTY_SIZE) { row ->
            val pokemon = party.get(row)
            val held = pokemon?.heldItem() ?: ItemStack.EMPTY
            PresetSlot(pokemon?.uuid, if (StoredMark.isStored(held)) StoredMark.prototype(held) else ItemStack.EMPTY)
        })
    }

    private fun dirty(player: ServerPlayer) = PresetData.of(player.server).setDirty()

    fun reject(player: ServerPlayer, reason: String): Boolean {
        player.displayClientMessage(Component.translatable("cobblemon_party_sidebar.message.$reason").withStyle(ChatFormatting.RED), true)
        return false
    }
}
