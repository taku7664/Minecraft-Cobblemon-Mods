package jbro.cobblemon.simplemyroom.room;

public final class DamagePolicy {
    private DamagePolicy() {
    }

    public static boolean canPlayerAttack(
        boolean canModifyTargetRoom,
        boolean preventPlayerDamage,
        boolean preventUnauthorizedEntityAttack
    ) {
        if (preventPlayerDamage) return false;
        return !preventUnauthorizedEntityAttack || canModifyTargetRoom;
    }
}
