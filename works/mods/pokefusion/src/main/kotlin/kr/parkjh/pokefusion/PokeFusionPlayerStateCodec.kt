package kr.parkjh.pokefusion

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack

object PokeFusionPlayerStateCodec {
    fun encode(state: PokeFusionPlayerState, registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { root ->
            root.putInt(VERSION_KEY, FORMAT_VERSION)
            if (!state.baseInput.isEmpty) root.put(BASE_KEY, state.baseInput.save(registries))
            root.put(MATERIALS_KEY, encodeItems(state.materialInputs, registries))
            root.put(PENDING_KEY, encodeItems(state.pendingOutputs, registries))
            root.putBoolean(LEGACY_MIGRATION_KEY, state.legacyMigrationComplete)
        }

    fun decode(root: CompoundTag, registries: HolderLookup.Provider): PokeFusionPlayerState {
        require(root.contains(VERSION_KEY, Tag.TAG_INT.toInt()) && root.getInt(VERSION_KEY) == FORMAT_VERSION) {
            "unsupported or missing player-state version"
        }
        val base = when {
            !root.contains(BASE_KEY) -> ItemStack.EMPTY
            !root.contains(BASE_KEY, Tag.TAG_COMPOUND.toInt()) -> throw IllegalArgumentException("base must be a compound")
            else -> parseItem(root.getCompound(BASE_KEY), registries, "base")
        }
        val materials = decodeItems(root, MATERIALS_KEY, registries)
        require(materials.size <= FusionMaterialLogic.MAX_MATERIALS) { "too many material inputs" }
        val pending = decodeItems(root, PENDING_KEY, registries)
        require(root.contains(LEGACY_MIGRATION_KEY, Tag.TAG_BYTE.toInt())) { "missing legacy migration flag" }
        return PokeFusionPlayerState(base, materials, pending, root.getBoolean(LEGACY_MIGRATION_KEY))
    }

    private fun encodeItems(items: List<ItemStack>, registries: HolderLookup.Provider): ListTag =
        ListTag().also { encoded ->
            items.filterNot(ItemStack::isEmpty).forEach { encoded.add(it.save(registries)) }
        }

    private fun decodeItems(root: CompoundTag, key: String, registries: HolderLookup.Provider): List<ItemStack> {
        require(root.contains(key, Tag.TAG_LIST.toInt())) { "$key must be a list" }
        val list = root.get(key) as ListTag
        require(list.isEmpty() || list.elementType == Tag.TAG_COMPOUND) { "$key must contain compounds" }
        return buildList {
            for (index in 0 until list.size) add(parseItem(list.getCompound(index), registries, "$key[$index]"))
        }
    }

    private fun parseItem(tag: CompoundTag, registries: HolderLookup.Provider, location: String): ItemStack =
        ItemStack.parse(registries, tag).orElseThrow {
            IllegalArgumentException("invalid item at $location")
        }.also { require(!it.isEmpty) { "empty item at $location" } }

    private const val FORMAT_VERSION = 1
    private const val VERSION_KEY = "version"
    private const val BASE_KEY = "base"
    private const val MATERIALS_KEY = "materials"
    private const val PENDING_KEY = "pending"
    private const val LEGACY_MIGRATION_KEY = "legacyMigrationComplete"
}
