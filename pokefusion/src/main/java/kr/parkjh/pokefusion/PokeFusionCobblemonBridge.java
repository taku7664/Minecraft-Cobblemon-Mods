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

    public static EvolutionNode evolutionNode(Pokemon pokemon) {
        return node(pokemon.getSpecies(), pokemon.getForm());
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
                        node(species, form),
                        node(form.getPreEvolution().getSpecies(), form.getPreEvolution().getForm())
                    ));
                }
                for (Evolution evolution : form.getEvolutions()) {
                    String resultSpeciesName = evolution.getResult().getSpecies();
                    if (resultSpeciesName == null || resultSpeciesName.isBlank()) {
                        continue;
                    }
                    Species resultSpecies = PokemonSpecies.getByName(resultSpeciesName);
                    if (resultSpecies != null) {
                        FormData resultForm = resolveForm(resultSpecies, evolution.getResult().getForm());
                        if (resultForm == null) {
                            continue;
                        }
                        edges.add(new EvolutionEdge(
                            node(species, form),
                            node(resultSpecies, resultForm)
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

    private static FormData resolveForm(Species species, String showdownFormId) {
        if (showdownFormId == null || showdownFormId.isBlank()) {
            return species.getStandardForm();
        }
        for (FormData form : species.getForms()) {
            if (form.formOnlyShowdownId().equalsIgnoreCase(showdownFormId)) {
                return form;
            }
        }
        return null;
    }

    private static EvolutionNode node(Species species, FormData form) {
        return new EvolutionNode(species.getResourceIdentifier().toString(), form.showdownId());
    }

    public record EvolutionNode(String speciesId, String formId) {
    }

    public record EvolutionEdge(EvolutionNode first, EvolutionNode second) {
    }
}
