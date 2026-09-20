package jbro.cobblemon.popupemotes.client;

record CustomEmotesLayout(
    int screenWidth,
    int screenHeight,
    int wheelCenterX,
    int wheelCenterY,
    float wheelScale,
    int panelLeft,
    int panelWidth,
    int nameFieldY,
    int urlFieldY,
    int primaryButtonsY,
    int orderButtonsY,
    int pageButtonsY,
    int statusTop,
    int statusBottom,
    int bottomButtonsY
) {
    private static final int WHEEL_BOUNDING_RADIUS = 97;

    static CustomEmotesLayout forScreen(int width, int height) {
        int desiredLeftWidth = Math.max(160, Math.min(220, (int)(width * 0.55F)));
        int leftWidth = Math.max(138, Math.min(desiredLeftWidth, width - 136));
        int panelLeft = leftWidth + 8;
        int panelWidth = Math.max(120, width - panelLeft - 8);
        int bottomButtonsY = height - 24;
        int addButtonY = bottomButtonsY - 22;
        float horizontalScale = (leftWidth - 16.0F) / (WHEEL_BOUNDING_RADIUS * 2.0F);
        float verticalScale = (addButtonY - 24.0F) / (WHEEL_BOUNDING_RADIUS * 2.0F);
        float wheelScale = Math.min(0.90F, Math.min(horizontalScale, verticalScale));
        int scaledRadius = (int)Math.ceil(WHEEL_BOUNDING_RADIUS * wheelScale);
        int wheelCenterX = Math.max(leftWidth / 2, scaledRadius + 8);
        int preferredCenterY = height / 2 - 4;
        int latestCenterY = addButtonY - 16 - scaledRadius;
        int wheelCenterY = Math.max(scaledRadius + 8, Math.min(preferredCenterY, latestCenterY));
        int statusBottom = bottomButtonsY - 7;
        int statusTop = statusBottom - 31;
        int pageButtonsY = Math.min(148, statusTop - 26);
        return new CustomEmotesLayout(
            width,
            height,
            wheelCenterX,
            wheelCenterY,
            wheelScale,
            panelLeft,
            panelWidth,
            34,
            70,
            100,
            126,
            pageButtonsY,
            statusTop,
            statusBottom,
            bottomButtonsY
        );
    }

    int panelRight() {
        return panelLeft + panelWidth;
    }

    int wheelLeft() {
        return wheelCenterX - scaledWheelRadius();
    }

    int wheelRight() {
        return wheelCenterX + scaledWheelRadius();
    }

    int wheelBottom() {
        return wheelCenterY + scaledWheelRadius();
    }

    int addButtonWidth() {
        return Math.min(100, panelLeft - 24);
    }

    int addButtonX() {
        return wheelCenterX - addButtonWidth() / 2;
    }

    int addButtonY() {
        return bottomButtonsY - 22;
    }

    private int scaledWheelRadius() {
        return (int)Math.ceil(WHEEL_BOUNDING_RADIUS * wheelScale);
    }
}
