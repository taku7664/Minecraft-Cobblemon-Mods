package jbro.cobblemon.battlespeed.client;

import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy;
import jbro.cobblemon.battlespeed.network.BattleSpeedSaveConfigPayload;
import jbro.cobblemon.battlespeed.network.BattleSpeedSetLevelPayload;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything here goes through the server: picking a level sends the same request as /battlespeed <level>, and an
 * operator's server settings are sent back as the whole config JSON, which the server checks, saves and applies.
 */
final class BattleSpeedConfigScreen {
    private static final String KEY = "cobblemon_battle_speed.config.";

    private BattleSpeedConfigScreen() {
    }

    static Screen create(Screen parent) {
        var minecraft = Minecraft.getInstance();
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(text("title"));
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory mine = builder.getOrCreateCategory(text("category.mine"));

        var synced = ClientBattleSpeedSettings.config();
        if (minecraft.getConnection() == null || minecraft.player == null) {
            mine.addEntry(entries.startTextDescription(text("not_connected")).build());
            return builder.build();
        }
        if (synced == null || !ClientPlayNetworking.canSend(BattleSpeedSetLevelPayload.TYPE)) {
            mine.addEntry(entries.startTextDescription(text("server_missing")).build());
            return builder.build();
        }

        int currentLevel = ClientBattleSpeedSettings.level();
        int[] chosenLevel = {currentLevel};
        addMyLevel(mine, entries, synced, chosenLevel, minecraft.player.hasPermissions(synced.permissions.change));

        boolean canEdit = minecraft.player.hasPermissions(synced.permissions.reload)
            && ClientPlayNetworking.canSend(BattleSpeedSaveConfigPayload.TYPE);
        String syncedJson = synced.toJson();
        BattleSpeedConfig working = BattleSpeedConfig.fromJson(syncedJson);
        if (canEdit) {
            addServerSettings(builder.getOrCreateCategory(text("category.server")), entries, working);
        }

        builder.setSavingRunnable(() -> {
            if (chosenLevel[0] != currentLevel) {
                ClientPlayNetworking.send(new BattleSpeedSetLevelPayload(chosenLevel[0]));
            }
            if (canEdit) {
                String edited = working.normalized().toJson();
                if (!edited.equals(syncedJson)) {
                    ClientPlayNetworking.send(new BattleSpeedSaveConfigPayload(edited));
                }
            }
        });
        return builder.build();
    }

    private static void addMyLevel(
        ConfigCategory category,
        ConfigEntryBuilder entries,
        BattleSpeedConfig synced,
        int[] chosenLevel,
        boolean canChange
    ) {
        if (!synced.enabled) {
            category.addEntry(entries.startTextDescription(text("mine.disabled")).build());
        } else if (!canChange) {
            category.addEntry(entries.startTextDescription(text("mine.no_permission")).build());
        } else if (synced.levelCount() < 2) {
            category.addEntry(entries.startTextDescription(
                text("mine.single_level", BattleSpeedPolicy.formatSpeed(synced.speedOf(1)))
            ).build());
        } else {
            category.addEntry(entries.startIntSlider(text("mine.level"), chosenLevel[0], 1, synced.levelCount())
                .setDefaultValue(synced.defaultLevel)
                .setTextGetter(level -> text(
                    "mine.level.value", level, BattleSpeedPolicy.formatSpeed(synced.speedOf(level))
                ))
                .setTooltip(text("mine.level.tooltip"))
                .setSaveConsumer(level -> chosenLevel[0] = level)
                .build());
        }
        category.addEntry(entries.startTextDescription(text("mine.description")).build());
    }

    private static void addServerSettings(ConfigCategory category, ConfigEntryBuilder entries, BattleSpeedConfig working) {
        var defaults = new BattleSpeedConfig().normalized();
        category.addEntry(entries.startTextDescription(text("server.description")).build());

        category.addEntry(entries.startBooleanToggle(text("server.enabled"), working.enabled)
            .setDefaultValue(defaults.enabled)
            .setTooltip(text("server.enabled.tooltip"))
            .setSaveConsumer(value -> working.enabled = value)
            .build());
        category.addEntry(entries.startDoubleList(text("server.speeds"), new ArrayList<>(working.speeds))
            .setDefaultValue(defaults.speeds)
            .setMin(BattleSpeedConfig.MIN_SPEED)
            .setMax(BattleSpeedConfig.MAX_SPEED)
            .setExpanded(true)
            .setInsertInFront(false)
            .setTooltip(text("server.speeds.tooltip"))
            .setSaveConsumer(value -> working.speeds = new ArrayList<>(value))
            .build());
        category.addEntry(entries.startIntField(text("server.default_level"), working.defaultLevel)
            .setDefaultValue(defaults.defaultLevel)
            .setMin(1)
            .setTooltip(text("server.default_level.tooltip"))
            .setSaveConsumer(value -> working.defaultLevel = value)
            .build());

        var battles = entries.startSubCategory(text("server.battles")).setExpanded(true);
        battles.add(entries.startBooleanToggle(text("server.battles.wild"), working.battles.wild)
            .setDefaultValue(defaults.battles.wild)
            .setSaveConsumer(value -> working.battles.wild = value)
            .build());
        battles.add(entries.startBooleanToggle(text("server.battles.npc"), working.battles.npc)
            .setDefaultValue(defaults.battles.npc)
            .setSaveConsumer(value -> working.battles.npc = value)
            .build());
        battles.add(entries.startBooleanToggle(text("server.battles.pvp"), working.battles.pvp)
            .setDefaultValue(defaults.battles.pvp)
            .setTooltip(text("server.battles.pvp.tooltip"))
            .setSaveConsumer(value -> working.battles.pvp = value)
            .build());
        category.addEntry(battles.build());

        category.addEntry(entries.startSelector(
                text("server.multiple_players"),
                new String[] {BattleSpeedConfig.SLOWEST, BattleSpeedConfig.NORMAL},
                working.multiplePlayers
            )
            .setDefaultValue(defaults.multiplePlayers)
            .setNameProvider(value -> text("server.multiple_players." + value))
            .setTooltip(text("server.multiple_players.tooltip"))
            .setSaveConsumer(value -> working.multiplePlayers = value)
            .build());

        var apply = entries.startSubCategory(text("server.apply")).setExpanded(true);
        apply.add(entries.startTextDescription(text("server.apply.description")).build());
        addPart(apply, entries, "message_waits", working.apply.messageWaits);
        addPart(apply, entries, "move_effects", working.apply.moveEffects);
        addPart(apply, entries, "battle_timers", working.apply.battleTimers);
        addPart(apply, entries, "client_animations", working.apply.clientAnimations);
        addPart(apply, entries, "model_animations", working.apply.modelAnimations);
        category.addEntry(apply.build());

        category.addEntry(entries.startIntField(text("server.skip_move_effects"), working.skipMoveEffectsFromLevel)
            .setDefaultValue(defaults.skipMoveEffectsFromLevel)
            .setMin(0)
            .setTooltip(text("server.skip_move_effects.tooltip"))
            .setSaveConsumer(value -> working.skipMoveEffectsFromLevel = value)
            .build());

        var permissions = entries.startSubCategory(text("server.permissions")).setExpanded(true);
        permissions.add(entries.startIntField(text("server.permissions.change"), working.permissions.change)
            .setDefaultValue(defaults.permissions.change)
            .setMin(0)
            .setMax(4)
            .setTooltip(text("server.permissions.change.tooltip"))
            .setSaveConsumer(value -> working.permissions.change = value)
            .build());
        permissions.add(entries.startIntField(text("server.permissions.reload"), working.permissions.reload)
            .setDefaultValue(defaults.permissions.reload)
            .setMin(0)
            .setMax(4)
            .setTooltip(text("server.permissions.reload.tooltip"))
            .setSaveConsumer(value -> working.permissions.reload = value)
            .build());
        category.addEntry(permissions.build());
    }

    private static void addPart(
        List<me.shedaniel.clothconfig2.api.AbstractConfigListEntry> list,
        ConfigEntryBuilder entries,
        String key,
        BattleSpeedConfig.Part part
    ) {
        list.add(entries.startBooleanToggle(text("server.apply." + key), part.enabled)
            .setDefaultValue(true)
            .setTooltip(text("server.apply." + key + ".tooltip"))
            .setSaveConsumer(value -> part.enabled = value)
            .build());
        list.add(entries.startDoubleField(text("server.apply.multiplier", text("server.apply." + key)), part.multiplier)
            .setDefaultValue(1.0)
            .setMin(BattleSpeedConfig.MIN_SPEED)
            .setMax(BattleSpeedConfig.MAX_SPEED)
            .setTooltip(text("server.apply.multiplier.tooltip"))
            .setSaveConsumer(value -> part.multiplier = value)
            .build());
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }
}
