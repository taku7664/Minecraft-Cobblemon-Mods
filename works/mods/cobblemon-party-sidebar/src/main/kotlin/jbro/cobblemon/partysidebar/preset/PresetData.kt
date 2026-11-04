package jbro.cobblemon.partysidebar.preset

import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.ContainerHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** One party row of a preset: which Pokemon stands there and which storage item it borrows (empty when none). */
class PresetSlot(var pokemon: UUID? = null, var reference: ItemStack = ItemStack.EMPTY)

/** A preset's rows and the name the player gave it (blank shows as "Preset N"). */
class Preset(val slots: List<PresetSlot> = List(PresetData.PARTY_SIZE) { PresetSlot() }, var name: String = "")

/**
 * A player's presets, item storage and Pokemon storage. The active preset always mirrors the live party (see
 * [PresetService.mirror]); switching applies another one, taking its Pokemon from the Pokemon storage.
 */
class PlayerPresets {
    val presets = mutableListOf<Preset>()
    var active = 0
    val storage: NonNullList<ItemStack> = NonNullList.withSize(PresetData.STORAGE_SIZE, ItemStack.EMPTY)
    val pokemonStorage: NonNullList<ItemStack> = NonNullList.withSize(PresetData.STORAGE_SIZE, ItemStack.EMPTY)

    fun activePreset(): Preset = presets[active]
}

/** Saved with the overworld under `data/cobblemon_party_sidebar.dat`, keyed by player UUID. */
class PresetData : SavedData() {
    private val players = HashMap<UUID, PlayerPresets>()

    fun get(player: UUID): PlayerPresets = players.getOrPut(player) { PlayerPresets() }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        players.forEach { (id, data) ->
            val entry = CompoundTag()
            entry.putUUID("Player", id)
            entry.putInt("Active", data.active)
            entry.put("Presets", ListTag().apply {
                data.presets.forEach { preset ->
                    add(CompoundTag().apply {
                        if (preset.name.isNotBlank()) putString("Name", preset.name)
                        put("Slots", ListTag().apply {
                            preset.slots.forEach { slot ->
                                add(CompoundTag().apply {
                                    slot.pokemon?.let { putUUID("Pokemon", it) }
                                    if (!slot.reference.isEmpty) put("Reference", slot.reference.save(registries))
                                })
                            }
                        })
                    })
                }
            })
            entry.put("Storage", ContainerHelper.saveAllItems(CompoundTag(), data.storage, registries))
            entry.put("PokemonStorage", ContainerHelper.saveAllItems(CompoundTag(), data.pokemonStorage, registries))
            list.add(entry)
        }
        tag.put("Players", list)
        return tag
    }

    companion object {
        const val PARTY_SIZE = 6
        const val STORAGE_SIZE = 54
        const val MAX_PRESETS = 18
        const val MAX_NAME_LENGTH = 16
        private const val NAME = "cobblemon_party_sidebar"

        private val FACTORY = Factory(::PresetData, ::load, DataFixTypes.LEVEL)

        fun of(server: MinecraftServer): PresetData = server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): PresetData {
            val data = PresetData()
            tag.getList("Players", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                val entry = raw as CompoundTag
                if (!entry.hasUUID("Player")) return@forEach
                val player = data.get(entry.getUUID("Player"))
                entry.getList("Presets", Tag.TAG_COMPOUND.toInt()).forEach { rawPreset ->
                    val presetTag = rawPreset as CompoundTag
                    val slots = presetTag.getList("Slots", Tag.TAG_COMPOUND.toInt())
                    player.presets += Preset(List(PARTY_SIZE) { index ->
                        val slot = if (index < slots.size) slots.getCompound(index) else CompoundTag()
                        PresetSlot(
                            if (slot.hasUUID("Pokemon")) slot.getUUID("Pokemon") else null,
                            if (slot.contains("Reference", Tag.TAG_COMPOUND.toInt())) {
                                ItemStack.parseOptional(registries, slot.getCompound("Reference"))
                            } else ItemStack.EMPTY,
                        )
                    }, presetTag.getString("Name"))
                }
                player.active = entry.getInt("Active").coerceIn(0, maxOf(0, player.presets.size - 1))
                ContainerHelper.loadAllItems(entry.getCompound("Storage"), player.storage, registries)
                ContainerHelper.loadAllItems(entry.getCompound("PokemonStorage"), player.pokemonStorage, registries)
                // Whatever sits in the storage belongs to it, even if an older save lacked the mark.
                player.storage.forEach(StoredMark::mark)
            }
            return data
        }
    }
}
