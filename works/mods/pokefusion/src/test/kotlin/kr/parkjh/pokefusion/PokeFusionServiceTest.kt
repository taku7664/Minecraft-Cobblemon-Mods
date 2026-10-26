package kr.parkjh.pokefusion

import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.abilities.CommonAbility
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonItemComponents
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.api.mark.Mark
import com.cobblemon.mod.common.api.mark.Marks
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.item.components.PokemonItemComponent
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.SharedConstants
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.MappedRegistry
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID
import java.util.IdentityHashMap

class PokeFusionServiceTest {
    private fun tag(alpha: Boolean = false, iv: Int = 0): CompoundTag = Pokemon().apply {
        species = PokemonSpecies.getByIdentifier(ResourceLocation.parse("cobblemon:cinderace"))!!
        moveSet.add(Moves.getExceptional().create())
    }.saveToNBT(registries).apply {
        putUUID("UUID", UUID.randomUUID())
        putBoolean("Alpha", alpha)
        putInt("Level", 66)
        putFloat("ScaleModifier", 1.08f)
        putInt("Fullness", 87)
        putFloat("RideStamina", 0.42f)
        put("RideBoosts", CompoundTag().apply { putFloat("SPEED", 1.5f) })
        put("InteractionCooldowns", CompoundTag().apply { putInt("test:interaction", 42) })
        getList("MoveSet", 10).getCompound(0).putInt("MovePP", 7)
        val ivs = CompoundTag().apply { put("Base", CompoundTag().apply { stats.forEach { putInt("cobblemon:$it", iv) } }) }
        ivs.put("HyperTrained", CompoundTag().apply { putInt("cobblemon:attack", 31) })
        put("IVs", ivs)
        put("PersistentData", CompoundTag().apply { putString("foreign_mod", "must survive") })
        put("UnknownFutureField", CompoundTag().apply { putLongArray("data", longArrayOf(1, Long.MAX_VALUE)) })
    }

    private fun item(tag: CompoundTag): ItemStack = ItemStack(pokemonItem).apply {
        set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply {
            PokemonItemFormat.putPokemonData(this, tag)
            putString("another_mod", "keep item metadata")
        }))
        set(DataComponents.CUSTOM_NAME, Component.literal("custom base"))
        set(CobblemonItemComponents.POKEMON_ITEM, PokemonItemComponent(
            ResourceLocation.parse(tag.getString("Species")), if (tag.getBoolean("Alpha")) setOf("male", "alpha_eyes") else setOf("male"), null
        ))
    }

    @Test fun `old instance loader loses alpha while the production bridge preserves it`() {
        val original = tag(alpha = true)
        val old = Pokemon().loadFromNBT(registries, original)
        assertFalse(old.isAlpha, "Must reproduce the upstream copyFrom regression")
        assertNotEquals(87, old.currentFullness, "The instance loader omits additional state, not just Alpha")
        val decoded = PokeFusionCobblemonBridge.loadPokemon(registries, original)
        assertTrue(decoded.isAlpha)
        assertEquals(original.getUUID("UUID"), decoded.uuid)
    }

    @TestFactory fun `base state survives all alpha shiny gender held-item and material-count combinations`(): List<DynamicTest> = buildList {
        for (alpha in listOf(false, true)) for (shiny in listOf(false, true))
            for (gender in listOf("MALE", "FEMALE", "GENDERLESS"))
                for (held in listOf(0, 1, 64)) for (count in 1..9) {
                    add(DynamicTest.dynamicTest("alpha=$alpha shiny=$shiny gender=$gender held=$held materials=$count") {
                        val source = tag(alpha, 7).apply {
                            putBoolean("Shiny", shiny); putString("Gender", gender)
                            putFloat("ScaleModifier", when (held) { 0 -> 0.5f; 1 -> 1.08f; else -> 3.0f })
                            if (held > 0) put("HeldItem", ItemStack(Items.DIAMOND, held).save(registries))
                        }
                        val base = item(source)
                        val materials = (1..count).map { n -> item(tag(!alpha, minOf(31, n * 4))) }
                        val before = base.copy()
                        val materialCopies = materials.map(ItemStack::copy)
                        val first = PokeFusionService.createResult(registries, base, materials)
                        assertTrue(first is PokeFusionService.Result.Success, "$first")
                        val output = (first as PokeFusionService.Result.Success).item
                        val actual = PokemonItemData.pokemonTag(output)!!
                        assertEquals(source.getUUID("UUID"), actual.getUUID("UUID"))
                        assertEquals(alpha, actual.getBoolean("Alpha"))
                        assertEquals(alpha, PokeFusionCobblemonBridge.loadPokemon(registries, actual).isAlpha, "Restoring the result must retain Alpha")
                        val expected = source.copy().apply {
                            val ivs = getCompound("IVs").getCompound("Base")
                            stats.forEach { ivs.putInt("cobblemon:$it", maxOf(7, minOf(31, count * 4))) }
                        }
                        assertEquals(expected, actual, "Only the six raw base IVs may change")
                        val expectedRoot = before.get(DataComponents.CUSTOM_DATA)!!.copyTag().apply { PokemonItemFormat.putPokemonData(this, expected) }
                        assertEquals(expectedRoot, output.get(DataComponents.CUSTOM_DATA)!!.copyTag())
                        assertEquals(before.get(DataComponents.CUSTOM_NAME), output.get(DataComponents.CUSTOM_NAME))
                        val expectedComponents = before.copy().apply { remove(DataComponents.CUSTOM_DATA) }
                        val actualComponents = output.copy().apply { remove(DataComponents.CUSTOM_DATA) }
                        assertTrue(ItemStack.matches(expectedComponents, actualComponents), "All other item components must survive")
                        assertTrue(ItemStack.matches(before, base), "Preview must not mutate base")
                        materials.zip(materialCopies).forEach { (a, b) -> assertTrue(ItemStack.matches(a, b), "Preview must not consume materials") }
                        assertEquals(1, output.count)
                        val again = PokeFusionService.createResult(registries, base, materials) as PokeFusionService.Result.Success
                        assertTrue(ItemStack.matches(output, again.item), "Repeated preview must be deterministic")
                        val reversed = PokeFusionService.createResult(registries, base, materials.reversed()) as PokeFusionService.Result.Success
                        assertTrue(ItemStack.matches(output, reversed.item), "Material order must not change the result")
                        val highest = minOf(31, count * 4)
                        assertEquals(List(count) { highest > 7 && minOf(31, (it + 1) * 4) == highest },
                            PokeFusionService.materialContributions(registries, base, materials))
                    })
                }
    }

    @TestFactory fun `every base and material IV value pair runs through the real fusion service`(): List<DynamicTest> = buildList {
        for (baseIv in 0..31) for (materialIv in 0..31) {
            add(DynamicTest.dynamicTest("baseIV=$baseIv materialIV=$materialIv") {
                val source = tag(true, baseIv)
                val material = tag(false, materialIv)
                // Shift different stats independently so a swapped stat mapping cannot pass.
                stats.forEachIndexed { index, stat ->
                    source.getCompound("IVs").getCompound("Base").putInt("cobblemon:$stat", (baseIv + index) % 32)
                    material.getCompound("IVs").getCompound("Base").putInt("cobblemon:$stat", (materialIv + index * 7) % 32)
                }
                val result = PokeFusionService.createResult(registries, item(source), listOf(item(material))) as PokeFusionService.Result.Success
                val data = PokemonItemData.pokemonTag(result.item)!!
                val expected = source.copy()
                stats.forEach { stat ->
                    val original = source.getCompound("IVs").getCompound("Base").getInt("cobblemon:$stat")
                    val incoming = material.getCompound("IVs").getCompound("Base").getInt("cobblemon:$stat")
                    val value = data.getCompound("IVs").getCompound("Base").getInt("cobblemon:$stat")
                    assertTrue(value >= original && value >= incoming && (value == original || value == incoming))
                    expected.getCompound("IVs").getCompound("Base").putInt("cobblemon:$stat", value)
                }
                assertEquals(expected, data, "Only actual base IV values may change")
            })
        }
    }

    @Test fun `invalid identity count version species and Pokemon codec data fail without consuming inputs`() {
        val base = item(tag(true, 20)); val valid = item(tag(false, 31))
        val broken = listOf(
            ItemStack.EMPTY, ItemStack(Items.STONE), valid.copy().apply { count = 2 },
            item(tag().apply { remove("UUID") }), item(tag().apply { putString("Species", "missing:unknown") }),
            item(tag().apply { putString("Alpha", "invalid boolean") }),
            item(tag().apply { getCompound("IVs").getCompound("Base").putInt("cobblemon:hp", 32) }),
            item(tag().apply { getCompound("IVs").getCompound("Base").putInt("cobblemon:hp", -1) }),
            item(tag().apply { getCompound("IVs").remove("HyperTrained") }),
            valid.copy().apply { set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { put("PTI_NBT", tag()) })) },
            valid.copy().apply { set(DataComponents.CUSTOM_DATA, CustomData.of(get(DataComponents.CUSTOM_DATA)!!.copyTag().apply { getCompound("cobblemon_to_item").putInt("version", 99) })) },
        )
        for (material in broken) {
            val before = base.copy()
            assertTrue(PokeFusionService.createResult(registries, base, listOf(material)) is PokeFusionService.Result.Failure)
            assertTrue(ItemStack.matches(before, base))
            assertEquals(listOf(false), PokeFusionService.materialContributions(registries, base, listOf(material)))
            assertTrue(PokeFusionService.createResult(registries, material, listOf(valid)) is PokeFusionService.Result.Failure)
            assertEquals(listOf(false), PokeFusionService.materialContributions(registries, material, listOf(valid)))
        }
        assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.MISSING_INPUT), PokeFusionService.createResult(registries, base, emptyList()))
        assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.MISSING_INPUT), PokeFusionService.createResult(registries, ItemStack.EMPTY, listOf(valid)))
        assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.INVALID_ITEM), PokeFusionService.createResult(registries, base, List(10) { item(tag()) }))
    }

    @Test fun `duplicate UUIDs in base or materials are rejected and never contribute`() {
        val base = item(tag(true, 7)); val material = item(tag(false, 31))
        for (materials in listOf(listOf(base.copy()), listOf(material, material.copy()))) {
            assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.INVALID_ITEM), PokeFusionService.createResult(registries, base, materials))
            assertEquals(List(materials.size) { false }, PokeFusionService.materialContributions(registries, base, materials))
        }
    }

    @Test fun `unrelated species and different forms are rejected`() {
        val base = item(tag())
        val unrelated = item(tag().apply { putString("Species", "cobblemon:pikachu") })
        assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.DIFFERENT_EVOLUTION_FAMILY), PokeFusionService.createResult(registries, base, listOf(unrelated)))
        val alternate = item(tag().apply {
            putString("FormId", "alternate")
            put("ForcedAspects", ListTag().apply { add(StringTag.valueOf("fusion-alternate")) })
        })
        assertEquals(PokeFusionService.Result.Failure(PokeFusionService.ValidationFailure.DIFFERENT_FORM), PokeFusionService.createResult(registries, base, listOf(alternate)))
    }

    @Test fun `material held items are returned separately and the base held item is retained`() {
        val base = item(tag().apply { put("HeldItem", ItemStack(Items.DIAMOND, 2).save(registries)) })
        val material = item(tag().apply { put("HeldItem", ItemStack(Items.GOLD_INGOT, 3).save(registries)) })
        val result = PokeFusionService.createResult(registries, base, listOf(material)) as PokeFusionService.Result.Success
        assertEquals(Items.GOLD_INGOT, result.materialHeldItems.single().item)
        assertEquals(3, result.materialHeldItems.single().count)
        assertEquals(PokemonItemData.pokemonTag(base)!!.getCompound("HeldItem"), PokemonItemData.pokemonTag(result.item)!!.getCompound("HeldItem"))
    }

    companion object {
        private val stats = listOf("hp", "attack", "defence", "special_attack", "special_defence", "speed")
        private lateinit var registries: RegistryAccess
        private lateinit var pokemonItem: PokemonItem
        private var speciesBefore = emptyMap<ResourceLocation, Species>()
        private var marksBefore = emptyMap<ResourceLocation, Mark>()
        private var movesBefore = emptyList<MoveTemplate>()

        private fun registerMove(move: MoveTemplate) {
            Moves::class.java.getDeclaredMethod("register", MoveTemplate::class.java).apply { isAccessible = true }.invoke(Moves, move)
        }

        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); ExperienceGroups.registerDefaults()
            if (runCatching { Cobblemon.config }.getOrNull() == null) Cobblemon.config = CobblemonConfig()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            movesBefore = Moves.all()
            if (Moves.count() == 0) registerMove(MoveTemplate.dummy("tackle"))
            registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
            // Standalone JUnit has no Fabric registration phase. Open the vanilla registry only long
            // enough to register this test item, then restore its original frozen state immediately.
            val registry = BuiltInRegistries.ITEM as MappedRegistry<*>
            val frozen = MappedRegistry::class.java.getDeclaredField("frozen").apply { isAccessible = true }
            val intrusive = MappedRegistry::class.java.getDeclaredField("unregisteredIntrusiveHolders").apply { isAccessible = true }
            val wasFrozen = frozen.get(registry)
            val previousIntrusive = intrusive.get(registry)
            try {
                frozen.set(registry, false); intrusive.set(registry, IdentityHashMap<Any, Any>())
                pokemonItem = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse("pokefusion:test_pokemon"), PokemonItem())
            } finally {
                intrusive.set(registry, previousIntrusive); frozen.set(registry, wasFrozen)
            }
            speciesBefore = PokemonSpecies.species.associateBy { it.resourceIdentifier }
            marksBefore = Marks.all().associateBy { it.identifier }
            val alpha = ResourceLocation.parse("cobblemon:mark_alpha")
            Marks.reload(mapOf(alpha to Mark(alpha, "alpha", "", null, null, alpha, null, null, 0F, null, listOf("alpha_eyes"))))
            val species = listOf("cinderace", "pikachu").map { id -> Species().apply {
                name = id.replaceFirstChar { it.uppercase() }; resourceIdentifier = ResourceLocation.parse("cobblemon:$id"); implemented = true
                abilities.add(Priority.LOWEST, CommonAbility(Abilities.DUMMY))
                forms += FormData(name = "Alternate").apply { aspects += "fusion-alternate" }
                initialize()
            } }
            PokemonSpecies.reload(species.associateBy { it.resourceIdentifier })
            PokeFusionService.refreshEvolutionFamilies()
            check(PokeFusionCobblemonBridge.loadPokemon(registries, PokeFusionServiceTest().tag(true)).isAlpha) { "Native Pokemon test registries are incomplete" }
        }

        @JvmStatic @AfterAll fun restoreRegistries() {
            PokemonSpecies.reload(speciesBefore); Marks.reload(marksBefore); PokeFusionService.refreshEvolutionFamilies()
            for (name in listOf("allMoves", "idMapping")) {
                (Moves::class.java.getDeclaredField(name).apply { isAccessible = true }.get(Moves) as MutableMap<*, *>).clear()
            }
            movesBefore.forEach(::registerMove)
        }
    }
}
