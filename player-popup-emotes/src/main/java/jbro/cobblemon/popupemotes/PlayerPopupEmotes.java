package jbro.cobblemon.popupemotes;

import jbro.cobblemon.popupemotes.network.EmoteNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerPopupEmotes implements ModInitializer {
    public static final String MOD_ID = "player_popup_emotes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        EmoteNetworking.registerServer();
        LOGGER.info("Initializing Player Popup Emotes");
    }
}
