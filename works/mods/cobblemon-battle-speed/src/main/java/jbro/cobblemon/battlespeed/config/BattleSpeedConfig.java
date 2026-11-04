package jbro.cobblemon.battlespeed.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the mod does is read from {@code config/cobblemon_battle_speed.json}. Missing fields fall back to the
 * defaults below, and out-of-range values are pulled back into range instead of failing the whole file.
 */
public final class BattleSpeedConfig {
    public static final double MIN_SPEED = 0.1;
    public static final double MAX_SPEED = 20.0;
    public static final String SLOWEST = "slowest";
    public static final String NORMAL = "normal";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Turns the whole mod off; every battle then runs at 1x. */
    public boolean enabled = true;
    /** Speed of each level, level 1 first. */
    public List<Double> speeds = new ArrayList<>(List.of(1.0, 2.0, 3.0, 4.0));
    /** Level a player starts at before choosing one. */
    public int defaultLevel = 1;
    public Battles battles = new Battles();
    /** With several players on the player side: "slowest" uses the slowest choice, "normal" runs at 1x. */
    public String multiplePlayers = SLOWEST;
    public Apply apply = new Apply();
    /** From this level up, move animations are skipped outright. 0 never skips. */
    public int skipMoveEffectsFromLevel = 0;
    public Permissions permissions = new Permissions();

    public static final class Battles {
        /** Battles against wild Pokémon. */
        public boolean wild = true;
        /** Battles against NPC trainers. */
        public boolean npc = true;
        /** Battles with players on both sides; when on, everyone runs at the slowest choice. */
        public boolean pvp = false;
    }

    public static final class Apply {
        /** The fixed pauses after battle messages. */
        public Part messageWaits = new Part();
        /** Pauses and delays inside move animations. */
        public Part moveEffects = new Part();
        /** Other battle timers: send-out spacing, faint waits, cries. */
        public Part battleTimers = new Part();
        /** Client HP bar and tile slide animations, so the HUD keeps up with the faster battle. */
        public Part clientAnimations = new Part();
        /** Pokémon model animations of the battlers: attack, hit and faint motions and the effects keyed to them. */
        public Part modelAnimations = new Part();
    }

    public static final class Part {
        public boolean enabled = true;
        /** Multiplies the level speed for this part only. */
        public double multiplier = 1.0;

        public Part() {
        }

        Part(boolean enabled, double multiplier) {
            this.enabled = enabled;
            this.multiplier = multiplier;
        }

        /** The speed this part runs at for the given level speed. A battle at 1x always stays at 1x. */
        public double factor(double levelSpeed) {
            if (!enabled || levelSpeed == 1.0) {
                return 1.0;
            }
            return clampSpeed(levelSpeed * multiplier);
        }
    }

    public static final class Permissions {
        /** Permission level for /battlespeed and /battlespeed <level>. */
        public int change = 0;
        /** Permission level for /battlespeed reload. */
        public int reload = 2;
    }

    public int levelCount() {
        return speeds.size();
    }

    public int clampLevel(int level) {
        return Math.max(1, Math.min(levelCount(), level));
    }

    public double speedOf(int level) {
        return speeds.get(clampLevel(level) - 1);
    }

    /** Pulls every value back into range so the rest of the mod can trust the config. */
    public BattleSpeedConfig normalized() {
        if (speeds == null || speeds.isEmpty()) {
            speeds = new ArrayList<>(List.of(1.0));
        } else {
            var cleaned = new ArrayList<Double>(speeds.size());
            for (Double speed : speeds) {
                cleaned.add(speed == null || speed.isNaN() ? 1.0 : clampSpeed(speed));
            }
            speeds = cleaned;
        }
        defaultLevel = clampLevel(defaultLevel);
        if (battles == null) {
            battles = new Battles();
        }
        multiplePlayers = multiplePlayers == null ? SLOWEST : multiplePlayers.trim().toLowerCase(Locale.ROOT);
        if (!multiplePlayers.equals(SLOWEST) && !multiplePlayers.equals(NORMAL)) {
            multiplePlayers = SLOWEST;
        }
        if (apply == null) {
            apply = new Apply();
        }
        apply.messageWaits = normalizedPart(apply.messageWaits);
        apply.moveEffects = normalizedPart(apply.moveEffects);
        apply.battleTimers = normalizedPart(apply.battleTimers);
        apply.clientAnimations = normalizedPart(apply.clientAnimations);
        apply.modelAnimations = normalizedPart(apply.modelAnimations);
        skipMoveEffectsFromLevel = Math.max(0, skipMoveEffectsFromLevel);
        if (permissions == null) {
            permissions = new Permissions();
        }
        permissions.change = clampPermission(permissions.change);
        permissions.reload = clampPermission(permissions.reload);
        return this;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static BattleSpeedConfig fromJson(String json) {
        BattleSpeedConfig config;
        try {
            config = GSON.fromJson(json, BattleSpeedConfig.class);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        if (config == null) {
            throw new IllegalArgumentException("the config file is empty");
        }
        return config.normalized();
    }

    /** Reads the file, writing the defaults first when it does not exist yet. Throws when the file is broken. */
    public static BattleSpeedConfig loadOrCreate(Path path) throws IOException {
        if (Files.notExists(path)) {
            var defaults = new BattleSpeedConfig().normalized();
            Files.createDirectories(path.getParent());
            Files.writeString(path, defaults.toJson(), StandardCharsets.UTF_8);
            return defaults;
        }
        return fromJson(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Writes the config through a temporary file so a crash mid-write never leaves half a file. */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, toJson(), StandardCharsets.UTF_8);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Part normalizedPart(Part part) {
        if (part == null) {
            return new Part();
        }
        double multiplier = Double.isNaN(part.multiplier) ? 1.0 : clampSpeed(part.multiplier);
        return new Part(part.enabled, multiplier);
    }

    static double clampSpeed(double speed) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    private static int clampPermission(int level) {
        return Math.max(0, Math.min(4, level));
    }
}
