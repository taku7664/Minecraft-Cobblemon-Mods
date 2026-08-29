package kr.parkjh.pokefusion;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps Minecraft-typed Cobblemon APIs behind Java signatures.
 *
 * <p>Some published Cobblemon artifacts retain intermediary names in Kotlin metadata after
 * remapping. Java reads the remapped JVM descriptors instead, so this boundary keeps clean
 * Kotlin builds independent from stale metadata names.</p>
 */
public final class PokeFusionCobblemonBridge {
    private static final Stat[] FUSION_STATS = {
        Stats.HP,
        Stats.ATTACK,
        Stats.DEFENCE,
        Stats.SPECIAL_ATTACK,
        Stats.SPECIAL_DEFENCE,
        Stats.SPEED
    };

    private PokeFusionCobblemonBridge() {
    }

    public static Pokemon loadPokemon(RegistryAccess registryAccess, CompoundTag tag) {
        return new Pokemon().loadFromNBT(registryAccess, tag);
    }

    public static CompoundTag savePokemon(Pokemon pokemon, RegistryAccess registryAccess) {
        return pokemon.saveToNBT(registryAccess, new CompoundTag());
    }

    public static int[] ivs(Pokemon pokemon) {
        int[] values = new int[FUSION_STATS.length];
        for (int index = 0; index < FUSION_STATS.length; index++) {
            values[index] = pokemon.getIvs().getOrDefault(FUSION_STATS[index]);
        }
        return values;
    }

    public static void mergeIvs(Pokemon base, List<Pokemon> materials) {
        for (Pokemon material : materials) {
            for (Stat stat : FUSION_STATS) {
                base.getIvs().set(stat, Math.max(
                    base.getIvs().getOrDefault(stat),
                    material.getIvs().getOrDefault(stat)
                ));
            }
        }
    }

    public static List<ItemStack> heldItems(List<Pokemon> pokemon) {
        List<ItemStack> items = new ArrayList<>();
        for (Pokemon entry : pokemon) {
            ItemStack heldItem = entry.heldItem();
            if (!heldItem.isEmpty()) {
                items.add(heldItem);
            }
        }
        return items;
    }

    public static ResourceLocation speciesId(Pokemon pokemon) {
        return pokemon.getSpecies().getResourceIdentifier();
    }

    public static String formName(Pokemon pokemon) {
        return pokemon.getForm().getName();
    }

    public static List<EvolutionEdge> evolutionEdges() {
        List<EvolutionEdge> edges = new ArrayList<>();
        for (Species species : PokemonSpecies.getSpecies()) {
            Map<String, FormData> forms = new LinkedHashMap<>();
            forms.put(species.getStandardForm().getName(), species.getStandardForm());
            for (FormData form : species.getForms()) {
                forms.putIfAbsent(form.getName(), form);
            }

            for (FormData form : forms.values()) {
                if (form.getPreEvolution() != null) {
                    edges.add(new EvolutionEdge(
                        species.getResourceIdentifier(),
                        form.getPreEvolution().getSpecies().getResourceIdentifier()
                    ));
                }
                for (Evolution evolution : form.getEvolutions()) {
                    String resultSpeciesName = evolution.getResult().getSpecies();
                    if (resultSpeciesName == null || resultSpeciesName.isBlank()) {
                        continue;
                    }
                    Species resultSpecies = PokemonSpecies.getByName(resultSpeciesName);
                    if (resultSpecies != null) {
                        edges.add(new EvolutionEdge(
                            species.getResourceIdentifier(),
                            resultSpecies.getResourceIdentifier()
                        ));
                    }
                }
            }
        }
        return edges;
    }

    public static int speciesCount() {
        return PokemonSpecies.getSpecies().size();
    }

    public record EvolutionEdge(ResourceLocation first, ResourceLocation second) {
    }
}
