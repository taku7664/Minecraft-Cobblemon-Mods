package jbro.cobblemon.bettermusic.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.config.FieldMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class MusicCatalogCompiler {
    private MusicCatalogCompiler() {
    }

    public static CompiledMusicConfiguration compile(
        String basePackId,
        List<MusicCatalog> catalogs,
        MusicCatalogSettings settings,
        MusicMappingOverrides overrides
    ) {
        Objects.requireNonNull(basePackId, "basePackId");
        Objects.requireNonNull(catalogs, "catalogs");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(overrides, "overrides");
        if (!basePackId.equals(settings.basePackId())) {
            throw new CatalogValidationException("Selected basePackId does not match settings: " + basePackId);
        }

        MusicCatalog base = selectBase(basePackId, catalogs);
        List<String> diagnostics = new ArrayList<>();
        List<MusicCatalog> extensions = eligibleExtensions(catalogs, diagnostics);

        Map<String, MusicCatalog.Track> tracks = mergeTracks(base, extensions, diagnostics);
        Map<String, MusicCatalog.Playlist> playlistSources = mergePlaylists(
            base, extensions, tracks.keySet(), diagnostics
        );
        Map<String, PlaylistDefinition> playlists = new LinkedHashMap<>();
        playlistSources.forEach((id, value) -> playlists.put(id, materialize(value, settings)));

        CatalogMappings baseMappings = base.mappings().orElseThrow();
        requireBaseMappings(baseMappings, playlists);
        Map<String, String> inactiveOverrides = new LinkedHashMap<>();
        FieldMusicConfig field = compileField(
            baseMappings.field(), overrides.field(), playlists, inactiveOverrides
        );
        BattleMusicConfig battle = compileBattle(
            baseMappings.battle(), overrides.battle(), playlists, inactiveOverrides
        );
        BetterMusicConfigSnapshot snapshot = new BetterMusicConfigSnapshot(
            settings.playback(), settings.audioEffects(), field, battle
        );

        Set<String> activeExtensions = new LinkedHashSet<>();
        for (MusicCatalog extension : extensions) {
            boolean contributesTrack = extension.tracks().entrySet().stream().anyMatch(entry ->
                !base.tracks().containsKey(entry.getKey()) && tracks.get(entry.getKey()) == entry.getValue()
            );
            boolean contributesPlaylist = extension.playlists().entrySet().stream().anyMatch(entry ->
                !base.playlists().containsKey(entry.getKey())
                    && playlistSources.get(entry.getKey()) == entry.getValue()
            );
            if (contributesTrack || contributesPlaylist) {
                activeExtensions.add(extension.packId());
            }
        }
        Map<String, String> trackEvents = new LinkedHashMap<>();
        tracks.forEach((id, track) -> trackEvents.put(id, track.eventId()));
        return new CompiledMusicConfiguration(
            snapshot,
            trackEvents,
            playlists,
            baseMappings,
            base.audioEvents().orElseThrow(),
            activeExtensions,
            diagnostics,
            inactiveOverrides
        );
    }

    private static MusicCatalog selectBase(String packId, List<MusicCatalog> catalogs) {
        List<MusicCatalog> matches = catalogs.stream()
            .filter(catalog -> catalog.kind() == MusicCatalog.Kind.BASE)
            .filter(catalog -> catalog.packId().equals(packId))
            .toList();
        if (matches.isEmpty()) {
            throw new CatalogValidationException("Base music catalog is not available: " + packId);
        }
        if (matches.size() > 1) {
            throw new CatalogValidationException("Multiple base music catalogs declare packId " + packId);
        }
        return matches.getFirst();
    }

    private static List<MusicCatalog> eligibleExtensions(
        List<MusicCatalog> catalogs,
        List<String> diagnostics
    ) {
        List<MusicCatalog> extensions = catalogs.stream()
            .filter(catalog -> catalog.kind() == MusicCatalog.Kind.EXTENSION)
            .sorted(Comparator.comparing(MusicCatalog::packId))
            .toList();
        Map<String, Long> counts = extensions.stream().collect(java.util.stream.Collectors.groupingBy(
            MusicCatalog::packId,
            LinkedHashMap::new,
            java.util.stream.Collectors.counting()
        ));
        Set<String> duplicatePackIds = new HashSet<>();
        counts.forEach((id, count) -> {
            if (count > 1) {
                duplicatePackIds.add(id);
                diagnostics.add("Disabled duplicate extension catalog packId '" + id + "'");
            }
        });
        return extensions.stream().filter(extension -> !duplicatePackIds.contains(extension.packId())).toList();
    }

    private static Map<String, MusicCatalog.Track> mergeTracks(
        MusicCatalog base,
        List<MusicCatalog> extensions,
        List<String> diagnostics
    ) {
        Map<String, MusicCatalog.Track> result = new LinkedHashMap<>(base.tracks());
        Map<String, Integer> extensionCounts = counts(extensions, MusicCatalog::tracks);
        for (MusicCatalog extension : extensions) {
            for (Map.Entry<String, MusicCatalog.Track> entry : extension.tracks().entrySet()) {
                String id = entry.getKey();
                if (base.tracks().containsKey(id)) {
                    diagnostics.add("Extension '" + extension.packId() + "' cannot replace base track '" + id + "'");
                    continue;
                }
                if (extensionCounts.getOrDefault(id, 0) > 1) {
                    diagnostics.add("Disabled conflicting extension track '" + id + "' from '" + extension.packId() + "'");
                    continue;
                }
                result.put(id, entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, MusicCatalog.Playlist> mergePlaylists(
        MusicCatalog base,
        List<MusicCatalog> extensions,
        Set<String> trackIds,
        List<String> diagnostics
    ) {
        Map<String, MusicCatalog.Playlist> result = new LinkedHashMap<>();
        for (Map.Entry<String, MusicCatalog.Playlist> entry : base.playlists().entrySet()) {
            requireTracks(entry.getKey(), entry.getValue(), trackIds, "Base catalog");
            result.put(entry.getKey(), entry.getValue());
        }
        Map<String, Integer> extensionCounts = counts(extensions, MusicCatalog::playlists);
        for (MusicCatalog extension : extensions) {
            for (Map.Entry<String, MusicCatalog.Playlist> entry : extension.playlists().entrySet()) {
                String id = entry.getKey();
                if (base.playlists().containsKey(id)) {
                    diagnostics.add("Extension '" + extension.packId() + "' cannot replace base playlist '" + id + "'");
                    continue;
                }
                if (extensionCounts.getOrDefault(id, 0) > 1) {
                    diagnostics.add("Disabled conflicting extension playlist '" + id + "' from '" + extension.packId() + "'");
                    continue;
                }
                List<String> missing = entry.getValue().tracks().stream()
                    .filter(track -> !trackIds.contains(track))
                    .toList();
                if (!missing.isEmpty()) {
                    diagnostics.add(
                        "Disabled extension playlist '" + id + "' from '" + extension.packId()
                            + "' because tracks are unavailable: " + missing
                    );
                    continue;
                }
                result.put(id, entry.getValue());
            }
        }
        return result;
    }

    private static <T> Map<String, Integer> counts(
        List<MusicCatalog> catalogs,
        Function<MusicCatalog, Map<String, T>> values
    ) {
        Map<String, Integer> counts = new HashMap<>();
        for (MusicCatalog catalog : catalogs) {
            for (String id : values.apply(catalog).keySet()) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static void requireTracks(
        String playlistId,
        MusicCatalog.Playlist playlist,
        Set<String> trackIds,
        String owner
    ) {
        List<String> missing = playlist.tracks().stream().filter(track -> !trackIds.contains(track)).toList();
        if (!missing.isEmpty()) {
            throw new CatalogValidationException(
                owner + " playlist '" + playlistId + "' references unavailable tracks: " + missing
            );
        }
    }

    private static PlaylistDefinition materialize(
        MusicCatalog.Playlist playlist,
        MusicCatalogSettings settings
    ) {
        return new PlaylistDefinition(
            playlist.selection().orElse(settings.selection()),
            playlist.volume().orElse(settings.volume()),
            playlist.betweenTracksSeconds().orElse(settings.playback().betweenTracksSeconds()),
            playlist.tracks()
        );
    }

    private static void requireBaseMappings(
        CatalogMappings mappings,
        Map<String, PlaylistDefinition> playlists
    ) {
        List<String> ids = new ArrayList<>();
        CatalogMappings.Field field = mappings.field();
        ids.add(field.defaultPlaylistId());
        ids.addAll(field.dimensions().values());
        ids.addAll(field.biomes().values());
        ids.addAll(field.biomePathContains().values());
        field.undergroundPlaylistId().ifPresent(ids::add);
        CatalogMappings.Battle battle = mappings.battle();
        ids.add(battle.wildPlaylistId());
        ids.add(battle.trainerPlaylistId());
        ids.add(battle.pvpPlaylistId());
        ids.addAll(battle.content().values());
        battle.legendaryPlaylistId().ifPresent(ids::add);
        battle.ultraBeastPlaylistId().ifPresent(ids::add);
        battle.pokemon().forEach(rule -> ids.add(rule.playlistId()));
        List<String> missing = ids.stream().filter(id -> !playlists.containsKey(id)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new CatalogValidationException("Base catalog mappings reference unavailable playlists: " + missing);
        }
    }

    private static FieldMusicConfig compileField(
        CatalogMappings.Field base,
        MusicMappingOverrides.Field overrides,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive
    ) {
        String defaultId = validOverride(
            "field.default", overrides.defaultPlaylistId(), base.defaultPlaylistId(), playlists, inactive
        );
        Map<String, String> dimensions = applyMapOverrides(
            "field.dimensions", base.dimensions(), overrides.dimensions(), playlists, inactive
        );
        Map<String, String> biomes = applyMapOverrides(
            "field.biomes", base.biomes(), overrides.biomes(), playlists, inactive
        );
        Map<String, String> paths = applyMapOverrides(
            "field.biomePathContains", base.biomePathContains(), overrides.biomePathContains(), playlists, inactive
        );
        Optional<String> underground = optionalOverride(
            "field.underground",
            overrides.undergroundPlaylistId(),
            base.undergroundPlaylistId(),
            playlists,
            inactive
        );
        return new FieldMusicConfig(
            playlists.get(defaultId),
            materializeMappings(dimensions, playlists),
            materializeMappings(biomes, playlists),
            materializeMappings(paths, playlists),
            underground.map(playlists::get)
        );
    }

    private static BattleMusicConfig compileBattle(
        CatalogMappings.Battle base,
        MusicMappingOverrides.Battle overrides,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive
    ) {
        String wild = validOverride("battle.wild", overrides.wildPlaylistId(), base.wildPlaylistId(), playlists, inactive);
        String trainer = validOverride(
            "battle.trainer", overrides.trainerPlaylistId(), base.trainerPlaylistId(), playlists, inactive
        );
        String pvp = validOverride("battle.pvp", overrides.pvpPlaylistId(), base.pvpPlaylistId(), playlists, inactive);
        Map<String, String> content = applyMapOverrides(
            "battle.content", base.content(), overrides.content(), playlists, inactive
        );
        Optional<String> legendary = optionalOverride(
            "battle.legendary", overrides.legendaryPlaylistId(), base.legendaryPlaylistId(), playlists, inactive
        );
        Optional<String> ultraBeast = optionalOverride(
            "battle.ultraBeast", overrides.ultraBeastPlaylistId(), base.ultraBeastPlaylistId(), playlists, inactive
        );
        List<BattleMusicConfig.PokemonRule> pokemon = new ArrayList<>();
        addPokemonRules("battle.pokemon.override", overrides.pokemon(), playlists, inactive, pokemon);
        addPokemonRules("battle.pokemon.base", base.pokemon(), playlists, inactive, pokemon);
        return new BattleMusicConfig(
            playlists.get(wild),
            playlists.get(trainer),
            playlists.get(pvp),
            materializeMappings(content, playlists),
            legendary.map(playlists::get),
            ultraBeast.map(playlists::get),
            pokemon
        );
    }

    private static void addPokemonRules(
        String prefix,
        List<CatalogMappings.PokemonMapping> rules,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive,
        List<BattleMusicConfig.PokemonRule> target
    ) {
        for (int index = 0; index < rules.size(); index++) {
            CatalogMappings.PokemonMapping rule = rules.get(index);
            PlaylistDefinition playlist = playlists.get(rule.playlistId());
            if (playlist == null) {
                inactive.put(prefix + "." + index, rule.playlistId());
                continue;
            }
            target.add(new BattleMusicConfig.PokemonRule(rule.species(), rule.only(), playlist));
        }
    }

    private static Optional<String> optionalOverride(
        String path,
        Optional<String> override,
        Optional<String> base,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive
    ) {
        if (override.isEmpty()) {
            return base;
        }
        String id = override.orElseThrow();
        if (playlists.containsKey(id)) {
            return Optional.of(id);
        }
        inactive.put(path, id);
        return base;
    }

    private static String validOverride(
        String path,
        Optional<String> override,
        String base,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive
    ) {
        if (override.isEmpty()) {
            return base;
        }
        String id = override.orElseThrow();
        if (playlists.containsKey(id)) {
            return id;
        }
        inactive.put(path, id);
        return base;
    }

    private static Map<String, String> applyMapOverrides(
        String path,
        Map<String, String> base,
        Map<String, String> overrides,
        Map<String, PlaylistDefinition> playlists,
        Map<String, String> inactive
    ) {
        Map<String, String> result = new LinkedHashMap<>(base);
        overrides.forEach((key, id) -> {
            if (playlists.containsKey(id)) {
                result.put(key, id);
            } else {
                inactive.put(path + "." + key, id);
            }
        });
        return result;
    }

    private static Map<String, PlaylistDefinition> materializeMappings(
        Map<String, String> ids,
        Map<String, PlaylistDefinition> playlists
    ) {
        Map<String, PlaylistDefinition> result = new LinkedHashMap<>();
        ids.forEach((key, id) -> result.put(key, playlists.get(id)));
        return result;
    }
}
