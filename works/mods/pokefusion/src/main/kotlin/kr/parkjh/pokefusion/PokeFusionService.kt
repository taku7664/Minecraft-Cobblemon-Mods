package kr.parkjh.pokefusion

import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.core.component.DataComponents
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import org.slf4j.LoggerFactory

object PokeFusionService {
    private val evolutionFamilies = EvolutionFamilyIndex<PokeFusionCobblemonBridge.EvolutionNode>()
    private val speciesFamilies = EvolutionFamilyIndex<String>()

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
        return PokemonItemData.isPokemonItem(stack)
    }

    fun createResult(player: ServerPlayer, baseStack: ItemStack, materialStacks: List<ItemStack>): Result =
        createResult(player.registryAccess(), baseStack, materialStacks)

    internal fun createResult(registries: RegistryAccess, baseStack: ItemStack, materialStacks: List<ItemStack>): Result {
        return try {
            createResultUnchecked(registries, baseStack, materialStacks)
        } catch (exception: Exception) {
            LOGGER.error("Pokefusion 결과 생성 중 오류가 발생했습니다.", exception)
            Result.Failure(ValidationFailure.PROCESSING_ERROR)
        }
    }

    private fun createResultUnchecked(registries: RegistryAccess, baseStack: ItemStack, materialStacks: List<ItemStack>): Result {
        if (baseStack.isEmpty || materialStacks.isEmpty()) return Result.Failure(ValidationFailure.MISSING_INPUT)
        if (materialStacks.size > FusionMaterialLogic.MAX_MATERIALS ||
            !validIdentities(baseStack, materialStacks)
        ) {
            return Result.Failure(ValidationFailure.INVALID_ITEM)
        }

        val base = loadPokemon(registries, baseStack) ?: return Result.Failure(ValidationFailure.INVALID_ITEM)
        val materials = materialStacks.map { loadPokemon(registries, it) ?: return Result.Failure(ValidationFailure.INVALID_ITEM) }

        compatibilityFailure(base, materials)?.let { return Result.Failure(it) }

        PokeFusionCobblemonBridge.mergeIvs(base, materials)

        val result = baseStack.copy()
        val existing = result.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        val original = requireNotNull(PokemonItemData.pokemonTag(baseStack))
        PokemonItemFormat.putPokemonData(existing, PokemonItemFormat.withIvs(original, PokeFusionCobblemonBridge.ivs(base)))
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(existing))
        result.count = 1
        return Result.Success(result, PokeFusionCobblemonBridge.heldItems(materials))
    }

    fun materialContributions(player: ServerPlayer, baseStack: ItemStack, materialStacks: List<ItemStack>): List<Boolean> =
        materialContributions(player.registryAccess(), baseStack, materialStacks)

    internal fun materialContributions(registries: RegistryAccess, baseStack: ItemStack, materialStacks: List<ItemStack>): List<Boolean> {
        if (materialStacks.size > FusionMaterialLogic.MAX_MATERIALS || !validIdentities(baseStack, materialStacks)) return List(materialStacks.size) { false }
        val base = loadPokemon(registries, baseStack) ?: return List(materialStacks.size) { false }
        val materials = materialStacks.map { loadPokemon(registries, it) }
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
            val baseNode = PokeFusionCobblemonBridge.evolutionNode(base)
            val materialNode = PokeFusionCobblemonBridge.evolutionNode(material)
            if (baseSpecies == materialSpecies && baseNode != materialNode) {
                return ValidationFailure.DIFFERENT_FORM
            }
            if (!evolutionFamilies.connected(baseNode, materialNode)) {
                if (speciesFamilies.connected(baseNode.speciesId(), materialNode.speciesId())) {
                    return ValidationFailure.DIFFERENT_FORM
                }
                return ValidationFailure.DIFFERENT_EVOLUTION_FAMILY
            }
        }
        return null
    }

    fun refreshEvolutionFamilies() {
        val edges = PokeFusionCobblemonBridge.evolutionEdges().map { it.first() to it.second() }
        evolutionFamilies.replace(edges)
        speciesFamilies.replace(edges.map { it.first.speciesId() to it.second.speciesId() })
        LOGGER.info(
            "Pokefusion 진화 계보를 갱신했습니다: 종 {}개, 연결 {}개",
            PokeFusionCobblemonBridge.speciesCount(),
            edges.size
        )
    }

    private fun validIdentities(base: ItemStack, materials: List<ItemStack>): Boolean {
        val ids = (listOf(base) + materials).map {
            PokemonItemData.pokemonTag(it)?.getUUID("UUID") ?: return false
        }
        return ids.toSet().size == ids.size
    }

    private fun loadPokemon(registries: RegistryAccess, stack: ItemStack): Pokemon? {
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val root = customData.copyTag()
            val pokemonData = PokemonItemFormat.pokemonData(root) ?: return null
            PokeFusionCobblemonBridge.loadPokemon(registries, pokemonData)
        } catch (_: Exception) {
            null
        }
    }

    private val LOGGER = LoggerFactory.getLogger(PokeFusionService::class.java)
}
