package jbro.cobblemon.bettermusic.integration.mbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReflectiveMbcContentLookupTest {
    @Test
    void readsContentIdsFromTheMbcClientSingletonWithoutALinkTimeDependency() throws Exception {
        var lookup = ReflectiveMbcContentLookup.load(
            getClass().getClassLoader(),
            FakeManagedBattleContentClient.class.getName()
        );
        UUID battleId = UUID.randomUUID();
        FakeManagedBattleContentClient.INSTANCE.contentId = "cobblemon_more_battle_content:battle_factory";

        assertEquals(
            "cobblemon_more_battle_content:battle_factory",
            lookup.contentId(battleId).orElseThrow()
        );
        assertEquals(battleId, FakeManagedBattleContentClient.INSTANCE.lastBattleId);
    }

    @Test
    void treatsNullAndBlankContentIdsAsAbsent() throws Exception {
        var lookup = ReflectiveMbcContentLookup.load(
            getClass().getClassLoader(),
            FakeManagedBattleContentClient.class.getName()
        );

        FakeManagedBattleContentClient.INSTANCE.contentId = null;
        assertTrue(lookup.contentId(UUID.randomUUID()).isEmpty());
        FakeManagedBattleContentClient.INSTANCE.contentId = "  ";
        assertTrue(lookup.contentId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void missingMbcApiFailsDuringOptionalAdapterRegistration() {
        assertThrows(
            ReflectiveOperationException.class,
            () -> ReflectiveMbcContentLookup.load(getClass().getClassLoader(), "missing.mbc.ClientApi")
        );
    }

    public static final class FakeManagedBattleContentClient {
        public static final FakeManagedBattleContentClient INSTANCE = new FakeManagedBattleContentClient();

        private UUID lastBattleId;
        private String contentId;

        public String contentId(UUID battleId) {
            lastBattleId = battleId;
            return contentId;
        }
    }
}
