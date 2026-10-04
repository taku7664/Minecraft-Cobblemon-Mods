package jbro.cobblemon.popupemotes.client;

final class EmotePlacement {
    private static final double CENTER_ABOVE_HEAD = 1.0;

    private EmotePlacement() {
    }

    static double centerY(double playerHeight, float configuredOffset) {
        return playerHeight + CENTER_ABOVE_HEAD + configuredOffset;
    }
}
