package jbro.cobblemon.bettermusic.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import jbro.cobblemon.bettermusic.catalog.CompiledMusicConfiguration;
import jbro.cobblemon.bettermusic.catalog.LegacyMusicConfigMigrator;
import jbro.cobblemon.bettermusic.catalog.MusicCatalog;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogCompiler;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogConfigStore;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;

public final class BetterMusicConfigManager {
    public static final String DEFAULT_BASE_PACK_ID = "cobleserver:official";
    private static final String LEGACY_DEFAULT_RESOURCE =
        "/assets/better_cobblemon_music/config_defaults/music.json";

    private final MusicCatalogConfigStore store;
    private volatile CompiledMusicConfiguration activeConfiguration;
    private volatile ReloadResult lastReload = new ReloadResult(
        Outcome.NO_VALID_CONFIG, "Music catalogs have not loaded yet", 0
    );
    private long revision;

    public BetterMusicConfigManager(Path configDirectory) {
        this.store = new MusicCatalogConfigStore(configDirectory);
    }

    public synchronized ReloadResult initialize() {
        try {
            store.initializeSettings(DEFAULT_BASE_PACK_ID);
            return publishResult(Outcome.INITIALIZED, "Initialized Better Cobblemon Music settings");
        } catch (Exception exception) {
            return publishFailure("Could not initialize Better Cobblemon Music settings: " + safeMessage(exception));
        }
    }

    public synchronized ReloadResult reloadCatalogs(List<CatalogDocument> documents) {
        Objects.requireNonNull(documents, "documents");
        List<MusicCatalog> catalogs = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (CatalogDocument document : documents) {
            try {
                catalogs.add(MusicCatalogParser.parse(new StringReader(document.json())));
            } catch (RuntimeException exception) {
                diagnostics.add("Rejected catalog from " + document.source() + ": " + safeMessage(exception));
            }
        }

        try {
            MusicCatalogSettings settings = store.loadSettings();
            MusicMappingOverrides overrides = loadOrMigrateOverrides(catalogs, settings, diagnostics);
            CompiledMusicConfiguration candidate = MusicCatalogCompiler.compile(
                settings.basePackId(), catalogs, settings, overrides
            );
            activeConfiguration = candidate;
            revision++;
            diagnostics.addAll(candidate.diagnostics());
            String message = "Applied Better Cobblemon Music catalogs";
            if (!diagnostics.isEmpty()) {
                message += "; " + String.join("; ", diagnostics);
            }
            return publishResult(Outcome.APPLIED, message);
        } catch (Exception exception) {
            String prefix = diagnostics.isEmpty() ? "" : String.join("; ", diagnostics) + "; ";
            return publishFailure(prefix + "Could not apply music catalogs: " + safeMessage(exception));
        }
    }

    public Optional<CompiledMusicConfiguration> activeConfiguration() {
        return Optional.ofNullable(activeConfiguration);
    }

    public Optional<BetterMusicConfigSnapshot> activeSnapshot() {
        return activeConfiguration().map(CompiledMusicConfiguration::snapshot);
    }

    public ReloadResult lastReload() {
        return lastReload;
    }

    public MusicCatalogConfigStore store() {
        return store;
    }

    private MusicMappingOverrides loadOrMigrateOverrides(
        List<MusicCatalog> catalogs,
        MusicCatalogSettings settings,
        List<String> diagnostics
    ) throws IOException {
        if (Files.isRegularFile(store.overridesFile()) || !Files.isRegularFile(store.legacyFile())) {
            return store.loadOverrides();
        }

        MusicMappingOverrides empty = MusicMappingOverrides.empty();
        CompiledMusicConfiguration defaults = MusicCatalogCompiler.compile(
            settings.basePackId(), catalogs, settings, empty
        );
        BetterMusicConfigSnapshot user = readLegacy(store.legacyFile());
        BetterMusicConfigSnapshot oldDefaults = readBundledLegacyDefaults();
        var migration = LegacyMusicConfigMigrator.migrate(user, oldDefaults, catalogs, defaults);
        diagnostics.addAll(migration.diagnostics());
        if (store.saveOverridesIfMissing(migration.overrides())) {
            diagnostics.add("Migrated legacy music.json custom mappings to overrides.json");
            return migration.overrides();
        }
        return store.loadOverrides();
    }

    private static BetterMusicConfigSnapshot readLegacy(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return MusicConfigParser.parse(reader);
        }
    }

    private static BetterMusicConfigSnapshot readBundledLegacyDefaults() throws IOException {
        InputStream stream = BetterMusicConfigManager.class.getResourceAsStream(LEGACY_DEFAULT_RESOURCE);
        if (stream == null) {
            throw new IOException("Bundled legacy defaults are missing");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return MusicConfigParser.parse(reader);
        }
    }

    private ReloadResult publishFailure(String message) {
        return publishResult(
            activeConfiguration == null ? Outcome.NO_VALID_CONFIG : Outcome.RETAINED_LAST_GOOD,
            message + (activeConfiguration == null
                ? "; no music configuration is active"
                : "; retained the last known good configuration")
        );
    }

    private ReloadResult publishResult(Outcome outcome, String message) {
        ReloadResult result = new ReloadResult(outcome, message, revision);
        lastReload = result;
        return result;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public record CatalogDocument(String source, String json) {
        public CatalogDocument {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(json, "json");
        }
    }

    public record ReloadResult(Outcome outcome, String message, long revision) {
        public ReloadResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(message, "message");
        }
    }

    public enum Outcome {
        INITIALIZED,
        APPLIED,
        RETAINED_LAST_GOOD,
        NO_VALID_CONFIG
    }
}
