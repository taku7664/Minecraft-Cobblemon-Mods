package jbro.cobblemon.betterbattlepresentation.client;

import java.util.UUID;

final class DynamaxAtmosphereClientState {
    private static final DynamaxAtmosphereTransition TRANSITION = new DynamaxAtmosphereTransition();

    private DynamaxAtmosphereClientState() {
    }

    static void setActive(UUID battleId, boolean active) {
        TRANSITION.setActive(battleId, active, System.nanoTime());
    }

    static float strength() {
        return TRANSITION.strength(System.nanoTime());
    }

    static void clear() {
        TRANSITION.clear();
    }
}
