package jbro.cobblemon.bettermusic.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import jbro.cobblemon.bettermusic.BetterCobblemonMusicClient;
import jbro.cobblemon.bettermusic.config.GlobalMusicSettings;
import jbro.cobblemon.bettermusic.config.GlobalMusicSettingsStore;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BetterMusicConfigScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(BetterCobblemonMusicClient.MOD_ID);
    private static final SystemToast.SystemToastId SAVE_TOAST = new SystemToast.SystemToastId();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve(BetterCobblemonMusicClient.MOD_ID)
        .resolve("music.json")
        .toAbsolutePath()
        .normalize();

    private BetterMusicConfigScreen() {
    }

    static Screen create(Screen parent) {
        GlobalMusicSettings initial;
        try {
            initial = GlobalMusicSettingsStore.load(CONFIG_FILE);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not open the Better Cobblemon Music configuration screen", exception);
            return BetterMusicConfigProblemScreen.loadFailure(parent, CONFIG_FILE, safeMessage(exception));
        }

        AtomicReference<GlobalMusicSettings> edited = new AtomicReference<>(initial);
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(text("title"))
            .setSavingRunnable(() -> save(edited.get()));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory playback = builder.getOrCreateCategory(text("category.playback"));

        addDouble(playback, entries, edited, "scan_interval", initial.scanIntervalSeconds(), 0.25, 60.0);
        addDouble(playback, entries, edited, "field_change_delay", initial.fieldChangeDelaySeconds(), 0.0, 60.0);
        addDouble(playback, entries, edited, "between_tracks", initial.betweenTracksSeconds(), 0.0, 600.0);
        addDouble(playback, entries, edited, "fade_in", initial.fadeInSeconds(), 0.0, 30.0);
        addDouble(playback, entries, edited, "fade_out", initial.fadeOutSeconds(), 0.0, 30.0);
        playback.addEntry(entries.startEnumSelector(
                text("selection"),
                PlaylistDefinition.Selection.class,
                initial.selection()
            )
            .setDefaultValue(PlaylistDefinition.Selection.SHUFFLE)
            .setEnumNameProvider(value -> text(
                "selection." + value.name().toLowerCase(Locale.ROOT)
            ))
            .setTooltip(text("selection.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(settings -> withSelection(settings, value)))
            .build());
        playback.addEntry(entries.startDoubleField(text("volume"), initial.volume())
            .setDefaultValue(1.0)
            .setMin(0.0)
            .setMax(4.0)
            .setTooltip(text("volume.tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(settings -> withVolume(settings, value)))
            .build());

        ConfigCategory advanced = builder.getOrCreateCategory(text("category.advanced"));
        advanced.addEntry(entries.startTextDescription(text("advanced.description")).build());
        return builder.build();
    }

    private static void addDouble(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        AtomicReference<GlobalMusicSettings> edited,
        String key,
        double initial,
        double minimum,
        double maximum
    ) {
        double defaultValue = switch (key) {
            case "scan_interval" -> 1.0;
            case "field_change_delay" -> 4.0;
            case "between_tracks" -> 0.0;
            case "fade_in", "fade_out" -> 1.0;
            default -> throw new IllegalArgumentException("Unknown setting: " + key);
        };
        category.addEntry(entries.startDoubleField(text(key), initial)
            .setDefaultValue(defaultValue)
            .setMin(minimum)
            .setMax(maximum)
            .setTooltip(text(key + ".tooltip"))
            .setSaveConsumer(value -> edited.updateAndGet(settings -> withDouble(settings, key, value)))
            .build());
    }

    private static GlobalMusicSettings withDouble(
        GlobalMusicSettings settings,
        String key,
        double value
    ) {
        return new GlobalMusicSettings(
            key.equals("scan_interval") ? value : settings.scanIntervalSeconds(),
            key.equals("field_change_delay") ? value : settings.fieldChangeDelaySeconds(),
            key.equals("between_tracks") ? value : settings.betweenTracksSeconds(),
            key.equals("fade_in") ? value : settings.fadeInSeconds(),
            key.equals("fade_out") ? value : settings.fadeOutSeconds(),
            settings.selection(),
            settings.volume()
        );
    }

    private static GlobalMusicSettings withSelection(
        GlobalMusicSettings settings,
        PlaylistDefinition.Selection selection
    ) {
        return new GlobalMusicSettings(
            settings.scanIntervalSeconds(), settings.fieldChangeDelaySeconds(),
            settings.betweenTracksSeconds(), settings.fadeInSeconds(), settings.fadeOutSeconds(),
            selection, settings.volume()
        );
    }

    private static GlobalMusicSettings withVolume(GlobalMusicSettings settings, double volume) {
        return new GlobalMusicSettings(
            settings.scanIntervalSeconds(), settings.fieldChangeDelaySeconds(),
            settings.betweenTracksSeconds(), settings.fadeInSeconds(), settings.fadeOutSeconds(),
            settings.selection(), volume
        );
    }

    private static void save(GlobalMusicSettings settings) {
        try {
            GlobalMusicSettingsStore.save(CONFIG_FILE, settings);
            toast(text("save.success"));
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not save Better Cobblemon Music settings from Mod Menu", exception);
            toast(Component.translatable("better_cobblemon_music.config.save.failure", safeMessage(exception)));
        }
    }

    private static void toast(Component message) {
        Minecraft client = Minecraft.getInstance();
        SystemToast.addOrUpdate(client.getToasts(), SAVE_TOAST, text("title"), message);
    }

    private static Component text(String suffix) {
        return Component.translatable("better_cobblemon_music.config." + suffix);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
