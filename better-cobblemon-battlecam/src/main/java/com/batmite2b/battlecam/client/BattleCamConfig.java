/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  net.fabricmc.loader.api.FabricLoader
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleViewContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import net.fabricmc.loader.api.FabricLoader;

public final class BattleCamConfig {
    private static final int CURRENT_CONFIG_VERSION = 3;
    public int configVersion = 3;
    public BattleCamState.Mode defaultMode = BattleCamState.Mode.AUTO;
    public BattleCamState.Mode defaultOwnBattleMode = null;
    public BattleCamState.Mode defaultSpectateMode = null;
    public boolean defaultEnabledInSpectate = true;
    public boolean pauseOwnBattleCameraWhenBattleUiCloses = true;
    public boolean debugLogging = true;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static BattleCamConfig loadOrCreate() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("battlecam.json");
        if (Files.exists(path, new LinkOption[0])) {
            BattleCamConfig cfg = BattleCamConfig.load(path);
            BattleCamConfig.save(path, cfg);
            return cfg;
        }
        BattleCamConfig cfg = new BattleCamConfig();
        cfg.normalize();
        BattleCamConfig.save(path, cfg);
        return cfg;
    }

    public static BattleCamConfig load(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            BattleCamConfig cfg = GSON.fromJson(reader, BattleCamConfig.class);
            if (cfg == null) {
                cfg = new BattleCamConfig();
            }
            cfg.normalize();
            return cfg;
        }
        catch (Exception ignored) {
            BattleCamConfig cfg = new BattleCamConfig();
            cfg.normalize();
            return cfg;
        }
    }

    public static void save(Path path, BattleCamConfig cfg) {
        try {
            Files.createDirectories(path.getParent(), new FileAttribute[0]);
            try (BufferedWriter writer = Files.newBufferedWriter(path, new OpenOption[0]);){
                GSON.toJson((Object)cfg, (Appendable)writer);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public BattleCamState.Mode defaultModeFor(BattleViewContext context) {
        return switch (context) {
            default -> throw new MatchException(null, null);
            case BattleViewContext.NONE -> this.defaultMode;
            case BattleViewContext.SPECTATING -> {
                if (this.defaultEnabledInSpectate) {
                    yield this.defaultSpectateMode;
                }
                yield BattleCamState.Mode.OFF;
            }
            case BattleViewContext.OWN_BATTLE -> this.defaultOwnBattleMode;
        };
    }

    private void normalize() {
        this.configVersion = 3;
        if (this.defaultMode == null) {
            this.defaultMode = BattleCamState.Mode.AUTO;
        }
        if (this.defaultOwnBattleMode == null) {
            this.defaultOwnBattleMode = this.defaultMode;
        }
        if (this.defaultSpectateMode == null) {
            this.defaultSpectateMode = this.defaultMode;
        }
    }
}
