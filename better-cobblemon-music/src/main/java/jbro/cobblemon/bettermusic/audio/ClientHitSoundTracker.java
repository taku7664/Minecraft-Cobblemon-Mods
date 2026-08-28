package jbro.cobblemon.bettermusic.audio;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ClientHitSoundTracker {
    public static final ClientHitSoundTracker INSTANCE = new ClientHitSoundTracker();

    private static final String BATTLE_PREFIX = "cobblemon.battle.";

    private final ArrayDeque<HitEffectiveness> pendingEffectiveness = new ArrayDeque<>();
    private final Map<String, Float> lastReportedHealth = new HashMap<>();
    private boolean moveWindowOpen;
    private boolean sawDamage;

    public synchronized void onBattleMessageKey(String translationKey) {
        if (translationKey == null || !translationKey.startsWith(BATTLE_PREFIX)) {
            return;
        }

        String key = translationKey.substring(BATTLE_PREFIX.length());
        if (key.equals("used_move") || key.equals("used_move_on")) {
            beginMove();
            return;
        }
        if (isSuperEffective(key)) {
            queue(HitEffectiveness.SUPER_EFFECTIVE);
            return;
        }
        if (isNotVeryEffective(key)) {
            queue(HitEffectiveness.NOT_VERY_EFFECTIVE);
            return;
        }
        if (key.equals("crit") || key.equals("crit_spread")) {
            return;
        }
        if (closesMoveWithoutDamage(key)) {
            closeMove();
            return;
        }
        if (moveWindowOpen && sawDamage) {
            closeMove();
        }
    }

    public synchronized Optional<HitEffectiveness> onHealthChange(
        String targetPnx,
        float currentClientHealth,
        float newHealth
    ) {
        if (targetPnx == null) {
            return Optional.empty();
        }

        float previousHealth = lastReportedHealth.getOrDefault(targetPnx, currentClientHealth);
        lastReportedHealth.put(targetPnx, newHealth);
        if (newHealth >= previousHealth || !moveWindowOpen) {
            return Optional.empty();
        }

        sawDamage = true;
        HitEffectiveness effectiveness = pendingEffectiveness.poll();
        return Optional.of(effectiveness == null ? HitEffectiveness.NORMAL : effectiveness);
    }

    public synchronized void clear() {
        closeMove();
        lastReportedHealth.clear();
    }

    private void queue(HitEffectiveness effectiveness) {
        if (moveWindowOpen) {
            pendingEffectiveness.add(effectiveness);
        }
    }

    private static boolean isSuperEffective(String key) {
        return key.equals("superEffective")
            || key.equals("superEffective_spread")
            || key.equals("extremelyEffective")
            || key.equals("extremelyEffective_spread");
    }

    private static boolean isNotVeryEffective(String key) {
        return key.equals("resisted")
            || key.equals("resisted_spread")
            || key.equals("mostlyIneffective")
            || key.equals("mostlyIneffective_spread");
    }

    private static boolean closesMoveWithoutDamage(String key) {
        return key.equals("hit_count")
            || key.equals("hit_count_singular")
            || key.equals("missed")
            || key.equals("immune")
            || key.equals("fail")
            || key.startsWith("fail.");
    }

    private void beginMove() {
        pendingEffectiveness.clear();
        lastReportedHealth.clear();
        moveWindowOpen = true;
        sawDamage = false;
    }

    private void closeMove() {
        pendingEffectiveness.clear();
        moveWindowOpen = false;
        sawDamage = false;
    }
}
