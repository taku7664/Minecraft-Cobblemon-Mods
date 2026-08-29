package kr.parkjh.pokefusion

import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory

object PokeFusionService {
    private val evolutionFamilies = EvolutionFamilyIndex<ResourceLocation>()

    enum class ValidationFailure {
        MISSING_INPUT,
        INVALID_ITEM,
        DIFFERENT_EVOLUTION_FAMILY,
        DIFFERENT_FORM,
        PROCESSING_ERROR
    }

    sealed interface Result {
        data class Success(val item: ItemStack, val materialHeldItems: List<ItemStack>) : Result
        data class Failure(val reason: ValidationFailure) : Result
    }

    fun isPokemonItem(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.count != 1) return false
        val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
        return PokemonToItemFormat.hasPokemonData(customData.copyTag())
    }

    fun createResult(player: ServerPlayer, baseStack: ItemStack, materialStacks: List<ItemStack>): Result {
        return try {
            createResultUnchecked(player, baseStack, materialStacks)
        } catch (exception: Exception) {
            LOGGER.error("Pokefusion 결과 생성 중 오류가 발생했습니다.", exception)
            Result.Failure(ValidationFailure.PROCESSING_ERROR)
        }
    }

    private fun createResultUnchecked(player: ServerPlayer, baseStack: ItemStack, materialStacks: List<ItemStack>): Result {
        if (baseStack.isEmpty || materialStacks.isEmpty()) return Result.Failure(ValidationFailure.MISSING_INPUT)
        if (materialStacks.size > FusionMaterialLogic.MAX_MATERIALS ||
            !isPokemonItem(baseStack) || materialStacks.any { !isPokemonItem(it) }
        ) {
            return Result.Failure(ValidationFailure.INVALID_ITEM)
        }

        val base = loadPokemon(player, baseStack) ?: return Result.Failure(ValidationFailure.INVALID_ITEM)
        val materials = materialStacks.map { loadPokemon(player, it) ?: return Result.Failure(ValidationFailure.INVALID_ITEM) }

        compatibilityFailure(base, materials)?.let { return Result.Failure(it) }

        PokeFusionCobblemonBridge.mergeIvs(base, materials)

        val result = baseStack.copy()
        val existing = result.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        PokemonToItemFormat.putPokemonData(existing, PokeFusionCobblemonBridge.savePokemon(base, player.registryAccess()))
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(existing))
        updateIvLore(result, base)
        result.count = 1
        return Result.Success(result, PokeFusionCobblemonBridge.heldItems(materials))
    }

    fun materialContributions(player: ServerPlayer, baseStack: ItemStack, materialStacks: List<ItemStack>): List<Boolean> {
        val base = loadPokemon(player, baseStack) ?: return List(materialStacks.size) { false }
        val materials = materialStacks.map { loadPokemon(player, it) }
        if (materials.any { it == null }) return List(materialStacks.size) { false }
        val validMaterials = materials.filterNotNull()
        if (compatibilityFailure(base, validMaterials) != null) return List(materialStacks.size) { false }

        val baseValues = PokeFusionCobblemonBridge.ivs(base)
        val materialValues = validMaterials.map(PokeFusionCobblemonBridge::ivs)
        return FusionMaterialLogic.contributions(baseValues, materialValues)
    }

    private fun compatibilityFailure(base: Pokemon, materials: List<Pokemon>): ValidationFailure? {
        for (material in materials) {
            val baseSpecies = PokeFusionCobblemonBridge.speciesId(base)
            val materialSpecies = PokeFusionCobblemonBridge.speciesId(material)
            val sameSpecies = baseSpecies == materialSpecies
            if (sameSpecies && PokeFusionCobblemonBridge.formName(base) != PokeFusionCobblemonBridge.formName(material)) {
                return ValidationFailure.DIFFERENT_FORM
            }
            if (!sameSpecies && !evolutionFamilies.connected(baseSpecies, materialSpecies)) {
                return ValidationFailure.DIFFERENT_EVOLUTION_FAMILY
            }
        }
        return null
    }

    fun refreshEvolutionFamilies() {
        val edges = PokeFusionCobblemonBridge.evolutionEdges().map { it.first() to it.second() }
        evolutionFamilies.replace(edges)
        LOGGER.info(
            "Pokefusion 진화 계보를 갱신했습니다: 종 {}개, 연결 {}개",
            PokeFusionCobblemonBridge.speciesCount(),
            edges.size
        )
    }

    private fun updateIvLore(stack: ItemStack, pokemon: Pokemon) {
        val ivs = PokeFusionCobblemonBridge.ivs(pokemon)
        val replacement = listOf(
            Component.literal("IVs: ").withStyle(ChatFormatting.LIGHT_PURPLE),
            Component.literal("  HP: ").withStyle(ChatFormatting.RED)
                .append(Component.literal(ivs[0].toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  Atk: ").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal(ivs[1].toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  Def: ").withStyle(ChatFormatting.BLUE))
                .append(Component.literal(ivs[2].toString()).withStyle(ChatFormatting.GRAY)),
            Component.literal("  SpAtk: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(ivs[3].toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  SpDef: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(ivs[4].toString()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  Spd: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(ivs[5].toString()).withStyle(ChatFormatting.GRAY))
        )
        val lines = stack.get(DataComponents.LORE)?.lines()?.toMutableList() ?: mutableListOf()
        val knownBlock = PokemonToItemFormat.findIvLoreBlock(lines.map { it.string })
        if (knownBlock != null) {
            lines.subList(knownBlock.first, knownBlock.last + 1).clear()
            lines.addAll(knownBlock.first, replacement)
        } else {
            lines.addAll(replacement)
        }
        stack.set(DataComponents.LORE, ItemLore(lines))
    }

    private fun loadPokemon(player: ServerPlayer, stack: ItemStack): Pokemon? {
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val root = customData.copyTag()
            val pokemonData = PokemonToItemFormat.pokemonData(root) ?: return null
            PokeFusionCobblemonBridge.loadPokemon(player.registryAccess(), pokemonData)
        } catch (_: Exception) {
            null
        }
    }

    private val LOGGER = LoggerFactory.getLogger(PokeFusionService::class.java)
}
