package jbro.cobblemon.popupemotes.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CustomEmotesLayoutTest {
    @Test
    void fitsWheelAndEveryControlInsideTheUsersScaledGui() {
        var layout = CustomEmotesLayout.forScreen(323, 240);

        assertTrue(layout.wheelLeft() >= 8);
        assertTrue(layout.wheelRight() < layout.panelLeft());
        assertTrue(layout.wheelRight() - layout.wheelLeft() >= 150);
        assertTrue(layout.wheelScale() >= 0.80F);
        assertTrue(layout.addButtonY() >= layout.wheelBottom() + 16);
        assertTrue(layout.addButtonY() + 20 <= layout.bottomButtonsY());
        assertTrue(layout.panelLeft() >= 0);
        assertTrue(layout.panelRight() <= 323);
        assertTrue(layout.nameFieldY() + 30 <= layout.urlFieldY());
        assertTrue(layout.urlFieldY() + 20 < layout.primaryButtonsY());
        assertTrue(layout.primaryButtonsY() + 20 < layout.orderButtonsY());
        assertTrue(layout.orderButtonsY() + 20 < layout.pageButtonsY());
        assertTrue(layout.pageButtonsY() + 20 <= layout.statusTop());
        assertTrue(layout.statusBottom() < layout.bottomButtonsY());
        assertTrue(layout.bottomButtonsY() + 20 <= 240);
    }
}
