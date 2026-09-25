package jbro.cobblemon.bettermusic.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import jbro.cobblemon.bettermusic.BetterCobblemonMusicClient;
import jbro.cobblemon.bettermusic.catalog.CatalogMappings;
import jbro.cobblemon.bettermusic.catalog.CompiledMusicConfiguration;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;
import jbro.cobblemon.bettermusic.config.AudioEffectsSettings;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterMusicConfigScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(BetterCobblemonMusicClient.MOD_ID);
    private static final SystemToast.SystemToastId SAVE_TOAST = new SystemToast.SystemToastId();
    private static final String USE_PACK_DEFAULT = "";
    private static volatile BetterMusicConfigManager manager;

    private BetterMusicConfigScreen() {
    }

    public static void configure(BetterMusicConfigManager configManager) {
        manager = java.util.Objects.requireNonNull(configManager, "configManager");
    }

    static Screen create(Screen parent) {
        BetterMusicConfigManager currentManager = manager;
        if (currentManager == null) {
            return BetterMusicConfigProblemScreen.loadFailure(parent, Path.of("settings.json"), "mod is not initialized");
        }
        CompiledMusicConfiguration compiled = currentManager.activeConfiguration().orElse(null);

        MusicCatalogSettings initialSettings;
        MusicMappingOverrides initialOverrides;
        try {
            initialSettings = currentManager.store().loadSettings();
            initialOverrides = currentManager.store().loadOverrides();
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not open the Better Cobblemon Music configuration screen", exception);
            return BetterMusicConfigProblemScreen.loadFailure(
                parent, currentManager.store().settingsFile(), safeMessage(exception)
            );
        }

        AtomicReference<MusicCatalogSettings> settings = new AtomicReference<>(initialSettings);
        AtomicReference<MusicMappingOverrides> overrides = new AtomicReference<>(initialOverrides);
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(text("title"))
            .setSavingRunnable(() -> save(currentManager, settings.get(), overrides.get()));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory playback = builder.getOrCreateCategory(text("category.playback"));
        addBasePack(playback, entries, settings, initialSettings, currentManager.availableBasePackIds());
        addPlayback(playback, entries, settings, initialSettings);
        addEffects(builder.getOrCreateCategory(text("category.effects")), entries, settings, initialSettings);
        if (compiled != null) {
            addMappings(builder, entries, compiled, overrides, initialOverrides);
        } else {
            ConfigCategory advanced = builder.getOrCreateCategory(text("category.advanced"));
            advanced.addEntry(entries.startTextDescription(Component.translatable(
                "better_cobblemon_music.config.mapping.no_active_base",
                currentManager.lastReload().message()
            )).build());
        }
        return builder.build();
    }

    private static void addBasePack(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        AtomicReference<MusicCatalogSettings> edited,
        MusicCatalogSettings initial,
        Set<String> available
    ) {
        Set<String> choices = new java.util.TreeSet<>(available);
        choices.add(initial.basePackId());
        category.addEntry(entries.startStringDropdownMenu(
                text("base_pack"), initial.basePackId(), Component::literal
            ).setSelections(choices).setDefaultValue(BetterMusicConfigManager.DEFAULT_BASE_PACK_ID)
            .setTooltip(text("base_pack.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(old -> new MusicCatalogSettings(
                value, old.playback(), old.selection(), old.volume(), old.audioEffects()
            )))
            .build());
    }

    private static void addPlayback(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        AtomicReference<MusicCatalogSettings> edited,
        MusicCatalogSettings initial
    ) {
        addDouble(category, entries, edited, "scan_interval", initial.playback().scanIntervalSeconds(), 0.25, 60.0);
        addDouble(category, entries, edited, "field_change_delay", initial.playback().fieldChangeDelaySeconds(), 0.0, 60.0);
        addDouble(category, entries, edited, "between_tracks", initial.playback().betweenTracksSeconds(), 0.0, 600.0);
        addDouble(category, entries, edited, "fade_in", initial.playback().fadeInSeconds(), 0.0, 30.0);
        addDouble(category, entries, edited, "fade_out", initial.playback().fadeOutSeconds(), 0.0, 30.0);
        category.addEntry(entries.startEnumSelector(text("selection"), PlaylistDefinition.Selection.class, initial.selection())
            .setDefaultValue(PlaylistDefinition.Selection.SHUFFLE)
            .setEnumNameProvider(value -> text("selection." + value.name().toLowerCase(Locale.ROOT)))
            .setTooltip(text("selection.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(old -> new MusicCatalogSettings(
                old.basePackId(), old.playback(), value, old.volume(), old.audioEffects()
            )))
            .build());
        category.addEntry(entries.startDoubleField(text("volume"), initial.volume())
            .setDefaultValue(1.0).setMin(0.0).setMax(4.0).setTooltip(text("volume.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(old -> new MusicCatalogSettings(
                old.basePackId(), old.playback(), old.selection(), value, old.audioEffects()
            )))
            .build());
    }

    private static void addEffects(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        AtomicReference<MusicCatalogSettings> edited,
        MusicCatalogSettings initial
    ) {
        AudioEffectsSettings effects = initial.audioEffects();
        category.addEntry(entries.startBooleanToggle(text("hit_sounds_enabled"), effects.hitSoundsEnabled())
            .setDefaultValue(true).setTooltip(text("hit_sounds_enabled.tooltip"))
            .setSaveConsumer(value -> updateEffects(edited, old -> new AudioEffectsSettings(
                value, old.hitSoundVolume(), old.lastPokemonHpEffectsEnabled(), old.lastPokemonHpEffectVolume()
            ))).build());
        category.addEntry(entries.startDoubleField(text("hit_sound_volume"), effects.hitSoundVolume())
            .setDefaultValue(1.0).setMin(0.0).setMax(AudioEffectsSettings.MAX_VOLUME)
            .setTooltip(text("hit_sound_volume.tooltip"))
            .setSaveConsumer(value -> updateEffects(edited, old -> new AudioEffectsSettings(
                old.hitSoundsEnabled(), value, old.lastPokemonHpEffectsEnabled(), old.lastPokemonHpEffectVolume()
            ))).build());
        category.addEntry(entries.startBooleanToggle(
                text("last_pokemon_hp_effects_enabled"), effects.lastPokemonHpEffectsEnabled()
            ).setDefaultValue(true).setTooltip(text("last_pokemon_hp_effects_enabled.tooltip"))
            .setSaveConsumer(value -> updateEffects(edited, old -> new AudioEffectsSettings(
                old.hitSoundsEnabled(), old.hitSoundVolume(), value, old.lastPokemonHpEffectVolume()
            ))).build());
        category.addEntry(entries.startDoubleField(
                text("last_pokemon_hp_effect_volume"), effects.lastPokemonHpEffectVolume()
            ).setDefaultValue(1.0).setMin(0.0).setMax(AudioEffectsSettings.MAX_VOLUME)
            .setTooltip(text("last_pokemon_hp_effect_volume.tooltip"))
            .setSaveConsumer(value -> updateEffects(edited, old -> new AudioEffectsSettings(
                old.hitSoundsEnabled(), old.hitSoundVolume(), old.lastPokemonHpEffectsEnabled(), value
            ))).build());
    }

    private static void addMappings(
        ConfigBuilder builder,
        ConfigEntryBuilder entries,
        CompiledMusicConfiguration compiled,
        AtomicReference<MusicMappingOverrides> edited,
        MusicMappingOverrides initial
    ) {
        List<String> playlists = compiled.playlists().keySet().stream().sorted().toList();
        CatalogMappings base = compiled.baseMappings();
        ConfigCategory field = builder.getOrCreateCategory(text("category.field_mappings"));
        addPlaylistChoice(field, entries, text("mapping.field.default"), initial.field().defaultPlaylistId(),
            base.field().defaultPlaylistId(), playlists, value -> edited.updateAndGet(old -> withFieldCore(old, "default", value)));
        base.field().undergroundPlaylistId().ifPresent(defaultId -> addPlaylistChoice(
            field, entries, text("mapping.field.underground"), initial.field().undergroundPlaylistId(), defaultId,
            playlists, value -> edited.updateAndGet(old -> withFieldCore(old, "underground", value))
        ));
        addFieldMap(field, entries, "dimensions", base.field().dimensions(), initial.field().dimensions(), playlists, edited);
        addFieldMap(field, entries, "biomes", base.field().biomes(), initial.field().biomes(), playlists, edited);
        addFieldMap(field, entries, "biome_path", base.field().biomePathContains(), initial.field().biomePathContains(), playlists, edited);

        ConfigCategory battle = builder.getOrCreateCategory(text("category.battle_mappings"));
        addBattleCore(battle, entries, "wild", initial.battle().wildPlaylistId(), base.battle().wildPlaylistId(), playlists, edited);
        addBattleCore(battle, entries, "trainer", initial.battle().trainerPlaylistId(), base.battle().trainerPlaylistId(), playlists, edited);
        addBattleCore(battle, entries, "pvp", initial.battle().pvpPlaylistId(), base.battle().pvpPlaylistId(), playlists, edited);
        base.battle().legendaryPlaylistId().ifPresent(value -> addBattleCore(
            battle, entries, "legendary", initial.battle().legendaryPlaylistId(), value, playlists, edited
        ));
        base.battle().ultraBeastPlaylistId().ifPresent(value -> addBattleCore(
            battle, entries, "ultra_beast", initial.battle().ultraBeastPlaylistId(), value, playlists, edited
        ));
        addBattleContent(battle, entries, base.battle().content(), initial.battle().content(), playlists, edited);
        addPokemonMappings(battle, entries, base.battle().pokemon(), initial.battle().pokemon(), playlists, edited);

        ConfigCategory advanced = builder.getOrCreateCategory(text("category.advanced"));
        advanced.addEntry(entries.startTextDescription(text("advanced.description")).build());
    }

    private static void addDouble(
        ConfigCategory category, ConfigEntryBuilder entries, AtomicReference<MusicCatalogSettings> edited,
        String key, double initial, double minimum, double maximum
    ) {
        double defaultValue = switch (key) {
            case "scan_interval" -> 1.0;
            case "field_change_delay" -> 4.0;
            case "between_tracks" -> 0.0;
            case "fade_in", "fade_out" -> 1.0;
            default -> throw new IllegalArgumentException("Unknown setting: " + key);
        };
        category.addEntry(entries.startDoubleField(text(key), initial)
            .setDefaultValue(defaultValue).setMin(minimum).setMax(maximum).setTooltip(text(key + ".tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(old -> withPlaybackValue(old, key, value))).build());
    }

    private static MusicCatalogSettings withPlaybackValue(MusicCatalogSettings old, String key, double value) {
        PlaybackSettings playback = old.playback();
        return new MusicCatalogSettings(old.basePackId(), new PlaybackSettings(
            key.equals("scan_interval") ? value : playback.scanIntervalSeconds(),
            key.equals("field_change_delay") ? value : playback.fieldChangeDelaySeconds(),
            key.equals("between_tracks") ? value : playback.betweenTracksSeconds(),
            key.equals("fade_in") ? value : playback.fadeInSeconds(),
            key.equals("fade_out") ? value : playback.fadeOutSeconds()
        ), old.selection(), old.volume(), old.audioEffects());
    }

    private static void updateEffects(
        AtomicReference<MusicCatalogSettings> edited,
        java.util.function.UnaryOperator<AudioEffectsSettings> update
    ) {
        edited.updateAndGet(old -> new MusicCatalogSettings(
            old.basePackId(), old.playback(), old.selection(), old.volume(), update.apply(old.audioEffects())
        ));
    }

    private static void addPlaylistChoice(
        ConfigCategory category, ConfigEntryBuilder entries, Component label, Optional<String> override,
        String packDefault, List<String> playlists, Consumer<Optional<String>> save
    ) {
        List<String> selections = new ArrayList<>();
        selections.add(USE_PACK_DEFAULT);
        selections.addAll(playlists);
        String initial = override.orElse(USE_PACK_DEFAULT);
        Component packDefaultDisplay = text("mapping.use_pack_default", packDefault);
        category.addEntry(entries.startDropdownMenu(
                label,
                initial,
                value -> PlaylistChoiceCodec.parseInput(value, packDefaultDisplay.getString()),
                value -> value.isEmpty() ? packDefaultDisplay : Component.literal(value)
            ).setSelections(selections).setDefaultValue(USE_PACK_DEFAULT)
            .setSaveConsumer(value -> save.accept(value.isEmpty() ? Optional.empty() : Optional.of(value)))
            .build());
    }

    private static void addFieldMap(
        ConfigCategory category, ConfigEntryBuilder entries, String type, Map<String, String> defaults,
        Map<String, String> overrides, List<String> playlists, AtomicReference<MusicMappingOverrides> edited
    ) {
        Set<String> keys = new LinkedHashSet<>(defaults.keySet());
        keys.addAll(overrides.keySet());
        keys.stream().sorted().forEach(key -> addPlaylistChoice(
            category, entries, Component.literal(label(type, key)), Optional.ofNullable(overrides.get(key)),
            defaults.getOrDefault(key, text("mapping.none").getString()), playlists,
            value -> edited.updateAndGet(old -> withFieldMap(old, type, key, value))
        ));
    }

    private static void addBattleCore(
        ConfigCategory category, ConfigEntryBuilder entries, String type, Optional<String> override,
        String packDefault, List<String> playlists, AtomicReference<MusicMappingOverrides> edited
    ) {
        addPlaylistChoice(category, entries, text("mapping.battle." + type), override, packDefault, playlists,
            value -> edited.updateAndGet(old -> withBattleCore(old, type, value)));
    }

    private static void addBattleContent(
        ConfigCategory category, ConfigEntryBuilder entries, Map<String, String> defaults,
        Map<String, String> overrides, List<String> playlists, AtomicReference<MusicMappingOverrides> edited
    ) {
        Set<String> keys = new LinkedHashSet<>(defaults.keySet());
        keys.addAll(overrides.keySet());
        keys.stream().sorted().forEach(key -> addPlaylistChoice(
            category, entries, Component.literal(label("content", key)), Optional.ofNullable(overrides.get(key)),
            defaults.getOrDefault(key, text("mapping.none").getString()), playlists,
            value -> edited.updateAndGet(old -> withBattleContent(old, key, value))
        ));
    }

    private static void addPokemonMappings(
        ConfigCategory category, ConfigEntryBuilder entries, List<CatalogMappings.PokemonMapping> defaults,
        List<CatalogMappings.PokemonMapping> overrides, List<String> playlists,
        AtomicReference<MusicMappingOverrides> edited
    ) {
        for (CatalogMappings.PokemonMapping rule : defaults) {
            Optional<String> current = overrides.stream()
                .filter(candidate -> candidate.species().equals(rule.species()) && candidate.only().equals(rule.only()))
                .map(CatalogMappings.PokemonMapping::playlistId).findFirst();
            addPlaylistChoice(category, entries, Component.literal(label("pokemon", String.join(", ", rule.species()))),
                current, rule.playlistId(), playlists,
                value -> edited.updateAndGet(old -> withPokemonOverride(old, rule, value)));
        }
    }

    private static MusicMappingOverrides withFieldCore(MusicMappingOverrides old, String type, Optional<String> value) {
        var field = old.field();
        return new MusicMappingOverrides(new MusicMappingOverrides.Field(
            type.equals("default") ? value : field.defaultPlaylistId(), field.dimensions(), field.biomes(),
            field.biomePathContains(), type.equals("underground") ? value : field.undergroundPlaylistId()
        ), old.battle());
    }

    private static MusicMappingOverrides withFieldMap(
        MusicMappingOverrides old, String type, String key, Optional<String> value
    ) {
        var field = old.field();
        Map<String, String> map = switch (type) {
            case "dimensions" -> editable(field.dimensions());
            case "biomes" -> editable(field.biomes());
            case "biome_path" -> editable(field.biomePathContains());
            default -> throw new IllegalArgumentException("Unknown field mapping: " + type);
        };
        putOrRemove(map, key, value);
        return new MusicMappingOverrides(new MusicMappingOverrides.Field(
            field.defaultPlaylistId(), type.equals("dimensions") ? map : field.dimensions(),
            type.equals("biomes") ? map : field.biomes(),
            type.equals("biome_path") ? map : field.biomePathContains(), field.undergroundPlaylistId()
        ), old.battle());
    }

    private static MusicMappingOverrides withBattleCore(MusicMappingOverrides old, String type, Optional<String> value) {
        var battle = old.battle();
        return new MusicMappingOverrides(old.field(), new MusicMappingOverrides.Battle(
            type.equals("wild") ? value : battle.wildPlaylistId(),
            type.equals("trainer") ? value : battle.trainerPlaylistId(),
            type.equals("pvp") ? value : battle.pvpPlaylistId(), battle.content(),
            type.equals("legendary") ? value : battle.legendaryPlaylistId(),
            type.equals("ultra_beast") ? value : battle.ultraBeastPlaylistId(), battle.pokemon()
        ));
    }

    private static MusicMappingOverrides withBattleContent(
        MusicMappingOverrides old, String key, Optional<String> value
    ) {
        var battle = old.battle();
        Map<String, String> content = editable(battle.content());
        putOrRemove(content, key, value);
        return new MusicMappingOverrides(old.field(), new MusicMappingOverrides.Battle(
            battle.wildPlaylistId(), battle.trainerPlaylistId(), battle.pvpPlaylistId(), content,
            battle.legendaryPlaylistId(), battle.ultraBeastPlaylistId(), battle.pokemon()
        ));
    }

    private static MusicMappingOverrides withPokemonOverride(
        MusicMappingOverrides old, CatalogMappings.PokemonMapping base, Optional<String> value
    ) {
        var battle = old.battle();
        List<CatalogMappings.PokemonMapping> pokemon = new ArrayList<>(battle.pokemon());
        pokemon.removeIf(rule -> rule.species().equals(base.species()) && rule.only().equals(base.only()));
        value.ifPresent(playlist -> pokemon.add(new CatalogMappings.PokemonMapping(base.species(), base.only(), playlist)));
        return new MusicMappingOverrides(old.field(), new MusicMappingOverrides.Battle(
            battle.wildPlaylistId(), battle.trainerPlaylistId(), battle.pvpPlaylistId(), battle.content(),
            battle.legendaryPlaylistId(), battle.ultraBeastPlaylistId(), pokemon
        ));
    }

    private static Map<String, String> editable(Map<String, String> source) {
        return new LinkedHashMap<>(source);
    }

    private static void putOrRemove(Map<String, String> target, String key, Optional<String> value) {
        if (value.isPresent()) {
            target.put(key, value.orElseThrow());
        } else {
            target.remove(key);
        }
    }

    private static String label(String type, String key) {
        return text("mapping.type." + type).getString() + ": " + key;
    }

    private static void save(
        BetterMusicConfigManager configManager,
        MusicCatalogSettings settings,
        MusicMappingOverrides overrides
    ) {
        try {
            configManager.store().saveSettings(settings);
            configManager.store().saveOverrides(overrides);
            Minecraft client = Minecraft.getInstance();
            client.reloadResourcePacks().whenCompleteAsync((ignored, failure) -> {
                if (failure == null && configManager.lastReload().outcome() == BetterMusicConfigManager.Outcome.APPLIED) {
                    toast(text("save.success"));
                } else {
                    toast(Component.translatable(
                        "better_cobblemon_music.config.save.failure",
                        failure == null ? configManager.lastReload().message() : safeMessage(failure)
                    ));
                }
            }, client);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not save Better Cobblemon Music settings from Mod Menu", exception);
            toast(Component.translatable("better_cobblemon_music.config.save.failure", safeMessage(exception)));
        }
    }

    private static void toast(Component message) {
        Minecraft client = Minecraft.getInstance();
        SystemToast.addOrUpdate(client.getToasts(), SAVE_TOAST, text("title"), message);
    }

    private static Component text(String suffix, Object... args) {
        return Component.translatable("better_cobblemon_music.config." + suffix, args);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
