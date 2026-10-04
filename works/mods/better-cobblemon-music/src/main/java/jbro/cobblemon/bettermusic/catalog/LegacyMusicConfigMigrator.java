package jbro.cobblemon.bettermusic.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

public final class LegacyMusicConfigMigrator {
    private LegacyMusicConfigMigrator() {
    }

    public static MigrationResult migrate(
        BetterMusicConfigSnapshot user,
        BetterMusicConfigSnapshot oldDefaults,
        List<MusicCatalog> catalogs,
        CompiledMusicConfiguration compiled
    ) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(oldDefaults, "oldDefaults");
        Objects.requireNonNull(catalogs, "catalogs");
        Objects.requireNonNull(compiled, "compiled");
        List<String> diagnostics = new ArrayList<>();
        Map<String, String> legacyTracks = legacyTrackIds(catalogs, diagnostics);
        PlaylistLookup lookup = new PlaylistLookup(compiled.playlists(), legacyTracks, diagnostics);

        MusicMappingOverrides.Field field = migrateField(user.field(), oldDefaults.field(), lookup, diagnostics);
        MusicMappingOverrides.Battle battle = migrateBattle(user.battle(), oldDefaults.battle(), lookup, diagnostics);
        return new MigrationResult(new MusicMappingOverrides(field, battle), diagnostics);
    }

    private static MusicMappingOverrides.Field migrateField(
        FieldMusicConfig user,
        FieldMusicConfig defaults,
        PlaylistLookup lookup,
        List<String> diagnostics
    ) {
        Optional<String> defaultPlaylist = changed(user.defaultPlaylist(), defaults.defaultPlaylist())
            ? lookup.resolve(user.defaultPlaylist(), "field.default")
            : Optional.empty();
        Map<String, String> dimensions = migrateMap(
            "field.dimensions", user.dimensions(), defaults.dimensions(), lookup, diagnostics
        );
        Map<String, String> biomes = migrateMap(
            "field.biomes", user.biomes(), defaults.biomes(), lookup, diagnostics
        );
        Map<String, String> paths = migrateMap(
            "field.biomePathContains", user.biomePathContains(), defaults.biomePathContains(), lookup, diagnostics
        );
        Optional<String> underground = Optional.empty();
        if (!user.underground().equals(defaults.underground())) {
            if (user.underground().isPresent()) {
                underground = lookup.resolve(user.underground().orElseThrow(), "field.underground");
            } else {
                diagnostics.add("Legacy field.underground removal cannot be represented and was not migrated");
            }
        }
        return new MusicMappingOverrides.Field(defaultPlaylist, dimensions, biomes, paths, underground);
    }

    private static MusicMappingOverrides.Battle migrateBattle(
        BattleMusicConfig user,
        BattleMusicConfig defaults,
        PlaylistLookup lookup,
        List<String> diagnostics
    ) {
        Optional<String> wild = changed(user.wild(), defaults.wild())
            ? lookup.resolve(user.wild(), "battle.wild") : Optional.empty();
        Optional<String> trainer = changed(user.trainer(), defaults.trainer())
            ? lookup.resolve(user.trainer(), "battle.trainer") : Optional.empty();
        Optional<String> pvp = changed(user.pvp(), defaults.pvp())
            ? lookup.resolve(user.pvp(), "battle.pvp") : Optional.empty();
        Map<String, String> content = migrateMap(
            "battle.content", user.content(), defaults.content(), lookup, diagnostics
        );
        Optional<String> legendary = migrateOptional(
            "battle.legendary", user.legendary(), defaults.legendary(), lookup, diagnostics
        );
        Optional<String> ultraBeast = migrateOptional(
            "battle.ultraBeast", user.ultraBeast(), defaults.ultraBeast(), lookup, diagnostics
        );
        List<CatalogMappings.PokemonMapping> pokemon = new ArrayList<>();
        if (!user.pokemon().equals(defaults.pokemon())) {
            for (int index = 0; index < user.pokemon().size(); index++) {
                BattleMusicConfig.PokemonRule rule = user.pokemon().get(index);
                if (defaults.pokemon().contains(rule)) {
                    continue;
                }
                lookup.resolve(rule.playlist(), "battle.pokemon[" + index + "]").ifPresent(playlist ->
                    pokemon.add(new CatalogMappings.PokemonMapping(rule.species(), rule.only(), playlist))
                );
            }
            if (user.pokemon().size() < defaults.pokemon().size()) {
                diagnostics.add("Removed legacy Pokémon rules cannot be represented and were not migrated");
            }
        }
        return new MusicMappingOverrides.Battle(
            wild, trainer, pvp, content, legendary, ultraBeast, pokemon
        );
    }

    private static Optional<String> migrateOptional(
        String path,
        Optional<PlaylistDefinition> user,
        Optional<PlaylistDefinition> defaults,
        PlaylistLookup lookup,
        List<String> diagnostics
    ) {
        if (user.equals(defaults)) {
            return Optional.empty();
        }
        if (user.isEmpty()) {
            diagnostics.add("Legacy " + path + " removal cannot be represented and was not migrated");
            return Optional.empty();
        }
        return lookup.resolve(user.orElseThrow(), path);
    }

    private static Map<String, String> migrateMap(
        String path,
        Map<String, PlaylistDefinition> user,
        Map<String, PlaylistDefinition> defaults,
        PlaylistLookup lookup,
        List<String> diagnostics
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, PlaylistDefinition> entry : user.entrySet()) {
            PlaylistDefinition oldDefault = defaults.get(entry.getKey());
            if (entry.getValue().equals(oldDefault)) {
                continue;
            }
            lookup.resolve(entry.getValue(), path + "." + entry.getKey())
                .ifPresent(id -> result.put(entry.getKey(), id));
        }
        for (String removed : defaults.keySet()) {
            if (!user.containsKey(removed)) {
                diagnostics.add("Removed legacy mapping " + path + "." + removed + " cannot be represented");
            }
        }
        return result;
    }

    private static boolean changed(PlaylistDefinition user, PlaylistDefinition defaults) {
        return !user.equals(defaults);
    }

    private static Map<String, String> legacyTrackIds(
        List<MusicCatalog> catalogs,
        List<String> diagnostics
    ) {
        Map<String, String> result = new HashMap<>();
        Set<String> conflicts = new java.util.HashSet<>();
        for (MusicCatalog catalog : catalogs) {
            for (Map.Entry<String, MusicCatalog.Track> track : catalog.tracks().entrySet()) {
                for (String legacyPath : track.getValue().legacyPaths()) {
                    String previous = result.putIfAbsent(legacyPath, track.getKey());
                    if (previous != null && !previous.equals(track.getKey())) {
                        conflicts.add(legacyPath);
                    }
                }
            }
        }
        for (String conflict : conflicts) {
            result.remove(conflict);
            diagnostics.add("Legacy track alias is ambiguous and was disabled: " + conflict);
        }
        return result;
    }

    public record MigrationResult(MusicMappingOverrides overrides, List<String> diagnostics) {
        public MigrationResult {
            Objects.requireNonNull(overrides, "overrides");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    private static final class PlaylistLookup {
        private final Map<String, PlaylistDefinition> playlists;
        private final Map<String, String> legacyTracks;
        private final List<String> diagnostics;

        private PlaylistLookup(
            Map<String, PlaylistDefinition> playlists,
            Map<String, String> legacyTracks,
            List<String> diagnostics
        ) {
            this.playlists = playlists;
            this.legacyTracks = legacyTracks;
            this.diagnostics = diagnostics;
        }

        private Optional<String> resolve(PlaylistDefinition legacy, String path) {
            List<String> converted = new ArrayList<>();
            for (String oldPath : legacy.tracks()) {
                String trackId = legacyTracks.get(oldPath);
                if (trackId == null) {
                    diagnostics.add("Did not migrate " + path + "; legacy track is unavailable: " + oldPath);
                    return Optional.empty();
                }
                converted.add(trackId);
            }
            PlaylistDefinition target = new PlaylistDefinition(
                legacy.selection(), legacy.volume(), legacy.betweenTracksSeconds(), converted
            );
            Comparator<String> preference = Comparator
                .comparing((String id) -> converted.size() == 1 && id.contains(":track/") ? 0 : 1)
                .thenComparing(Function.identity());
            Optional<String> result = playlists.entrySet().stream()
                .filter(entry -> entry.getValue().equals(target))
                .map(Map.Entry::getKey)
                .sorted(preference)
                .findFirst();
            if (result.isEmpty()) {
                diagnostics.add("Did not migrate " + path + "; no catalog playlist matches " + converted);
            }
            return result;
        }
    }
}
