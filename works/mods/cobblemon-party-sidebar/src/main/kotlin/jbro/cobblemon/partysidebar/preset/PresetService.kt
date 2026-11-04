package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import jbro.cobblemon.partysidebar.network.PresetActionPayload
import jbro.cobblemon.partysidebar.network.PresetStatePayload
import jbro.cobblemon.toitem.api.PokemonItemData
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Party presets and the two storages behind them.
 *
 * The active preset is the live party: every few ticks it takes down which Pokemon stands in each row and which
 * storage item each one borrows. Switching to another preset puts the Pokemon it does not use into the Pokemon storage
 * as items, takes its own Pokemon out of the Pokemon storage (or the player inventory, for one just itemized), returns
 * every borrowed item to the item storage and lends the new preset's items out again. The PC is not touched. Switching
 * happens only outside battle, so a battle never has to be unwound.
 *
 * A row whose preset lends an item the Pokemon is not holding, or names a Pokemon that is not there, is reported as
 * missing, and the sidebar shows it in red.
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

    fun storage(player: ServerPlayer): StorageContainer =
        StorageContainer(data(player).storage, StorageKind.ITEMS) { dirty(player) }

    fun pokemonStorage(player: ServerPlayer): StorageContainer =
        StorageContainer(data(player).pokemonStorage, StorageKind.POKEMON) { dirty(player) }

    fun handle(player: ServerPlayer, payload: PresetActionPayload) {
        when (payload.action) {
            PresetActionPayload.Action.SELECT -> apply(player, data(player), payload.index)
            PresetActionPayload.Action.ADD -> add(player)
            PresetActionPayload.Action.DELETE -> delete(player)
            PresetActionPayload.Action.RENAME -> rename(player, payload.name)
            PresetActionPayload.Action.OPEN_ITEMS -> openStorage(player, StorageKind.ITEMS)
            PresetActionPayload.Action.OPEN_POKEMON -> openStorage(player, StorageKind.POKEMON)
        }
        sync(player, force = true)
    }

    fun tick(server: MinecraftServer) {
        if (++ticks % TICK_INTERVAL != 0) return
        for (player in server.playerList.players) {
            try {
                val menu = player.containerMenu
                if (menu !is StorageMenu || menu.kind != StorageKind.ITEMS) returnLeakedItems(player)
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

    /** A new preset starts empty: switching to it puts the whole party into the Pokemon storage. */
    private fun add(player: ServerPlayer) {
        val data = data(player)
        if (data.presets.size >= PresetData.MAX_PRESETS) {
            reject(player, "preset_limit")
            return
        }
        data.presets += Preset()
        if (!apply(player, data, data.presets.lastIndex)) data.presets.removeAt(data.presets.lastIndex)
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

    private fun rename(player: ServerPlayer, name: String) {
        data(player).activePreset().name = name.trim().take(PresetData.MAX_NAME_LENGTH)
        dirty(player)
    }

    private fun openStorage(player: ServerPlayer, kind: StorageKind) {
        if (!player.containerMenu.carried.isEmpty || !player.inventoryMenu.carried.isEmpty) return
        val container = if (kind == StorageKind.ITEMS) storage(player) else pokemonStorage(player)
        val title = if (kind == StorageKind.ITEMS) "item_storage" else "pokemon_storage"
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> StorageMenu(kind, id, inventory, container) },
            Component.translatable("container.cobblemon_party_sidebar.$title")))
    }

    private fun itemUuid(stack: ItemStack): UUID? = PokemonItemData.pokemonTag(stack)?.getUUID("UUID")

    private fun apply(player: ServerPlayer, data: PlayerPresets, index: Int): Boolean {
        if (index !in data.presets.indices) return false
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) return reject(player, "preset_in_battle")
        if (!player.containerMenu.carried.isEmpty) return reject(player, "busy")
        mirror(player, data)
        if (index == data.active) return true
        val switch = PresetSwitch(Cobblemon.storage.getParty(player), Cobblemon.storage.getPC(player), data.pokemonStorage,
            pokemonStorage(player), storage(player), player.inventory, PokemonPacker.Items(player.registryAccess()))
        if (switch.current().any { it.entity?.isBusy == true }) return reject(player, "busy")
        return try {
            when (val result = switch.run(data.presets[index])) {
                is PresetSwitch.Result.Refused -> reject(player, result.reason)
                is PresetSwitch.Result.Done -> {
                    data.active = index
                    dirty(player)
                    CobblemonPartySidebar.LOGGER.info("[party-preset] player={} preset={} arriving={} leaving={}",
                        player.scoreboardName, index + 1, result.arriving, result.leaving)
                    true
                }
            }
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.error("Preset switch failed for {}", player.scoreboardName, failure)
            reject(player, "failed")
        }
    }

    /** Brings the active preset up to date with the party; true when it changed. */
    fun mirror(player: ServerPlayer, data: PlayerPresets): Boolean {
        val party = Cobblemon.storage.getParty(player)
        return PresetSwitch.mirror(data.activePreset(), party) { id ->
            party.any { it.uuid == id } || data.pokemonStorage.any { itemUuid(it) == id } ||
                Cobblemon.storage.getPC(player).any { it.uuid == id }
        }
    }

    private fun state(player: ServerPlayer, data: PlayerPresets): PresetStatePayload {
        val party = Cobblemon.storage.getParty(player)
        val slots = data.activePreset().slots
        val missing = slots.mapIndexed { row, slot ->
            val pokemon = party.get(row)
            val held = pokemon?.heldItem() ?: ItemStack.EMPTY
            val satisfied = StoredMark.isStored(held) && StoredMark.sameItem(held, slot.reference)
            if (slot.reference.isEmpty || pokemon == null || pokemon.uuid != slot.pokemon || satisfied) ItemStack.EMPTY
            else slot.reference.copy()
        }
        val missingPokemon = slots.mapIndexed { row, slot -> slot.pokemon != null && party.get(row)?.uuid != slot.pokemon }
        return PresetStatePayload(data.active, data.presets.size, data.activePreset().name, missing, missingPokemon)
    }

    /**
     * A storage item that reached the player inventory outside the item storage window left a Pokemon some other way
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
