package jbro.cobblemon.battlespeed.server;

import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy;
import net.minecraft.network.chat.Component;

/** Chat feedback shared by the command and the settings screen; Korean fallbacks for clients without the mod. */
final class BattleSpeedText {
    private static final String KEY = "cobblemon_battle_speed.command.";

    private BattleSpeedText() {
    }

    static Component message(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(KEY + key, fallback, args);
    }

    static Component current(BattleSpeedConfig config, int level) {
        return message("current", "현재 배틀 배속: %s단계 (%s배)", level, speed(config, level));
    }

    static Component changed(BattleSpeedConfig config, int level) {
        return message("changed", "배틀 배속을 %s단계 (%s배)로 바꿨습니다.", level, speed(config, level));
    }

    static Component disabled() {
        return message("disabled", "배틀 배속이 꺼져 있습니다.");
    }

    static Component invalidLevel(BattleSpeedConfig config) {
        return message("invalid_level", "배속 단계는 1부터 %s까지입니다.", config.levelCount());
    }

    static Component notApplied() {
        return message("not_applied", "지금 하는 배틀에는 배속이 적용되지 않습니다.");
    }

    static Component noPermission() {
        return message("no_permission", "이 설정을 바꿀 권한이 없습니다.");
    }

    private static String speed(BattleSpeedConfig config, int level) {
        return BattleSpeedPolicy.formatSpeed(config.speedOf(level));
    }
}
