package jbro.cobblemon.popupemotes.client;

import java.util.ArrayList;
import java.util.List;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteConfigStore;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteDefinition;
import jbro.cobblemon.popupemotes.client.custom.RemoteEmoteManager;
import net.minecraft.network.chat.Component;

final class EmoteWheelPages {
    private EmoteWheelPages() {
    }

    static int pageCount() {
        return CustomEmoteConfigStore.catalog().pageCount();
    }

    static List<WheelEmote> page(int pageIndex) {
        var result = new ArrayList<WheelEmote>();
        for (CustomEmoteDefinition emote : CustomEmoteConfigStore.catalog().page(pageIndex)) {
            result.add(new WheelEmote(
                emote.url(),
                Component.literal(emote.name()),
                () -> RemoteEmoteManager.texture(emote.url())
            ));
        }
        return List.copyOf(result);
    }
}
