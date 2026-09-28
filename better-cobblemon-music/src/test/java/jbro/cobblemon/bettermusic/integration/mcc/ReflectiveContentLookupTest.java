package jbro.cobblemon.bettermusic.integration.mcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReflectiveContentLookupTest {
    @Test
    void readsContentIdsFromTheMccClientSingletonWithoutALinkTimeDependency() throws Exception {
        var lookup = ReflectiveContentLookup.load(
            getClass().getClassLoader(),
            FakeManagedBattleContentClient.class.getName()
        );
        UUID battleId = UUID.randomUUID();
        FakeManagedBattleContentClient.INSTANCE.contentId = "more_cobblemon_contents:battle_factory";

        assertEquals(
            "more_cobblemon_contents:battle_factory",
            lookup.contentId(battleId).orElseThrow()
        );
        assertEquals(battleId, FakeManagedBattleContentClient.INSTANCE.lastBattleId);
    }

    @Test
    void treatsNullAndBlankContentIdsAsAbsent() throws Exception {
        var lookup = ReflectiveContentLookup.load(
            getClass().getClassLoader(),
            FakeManagedBattleContentClient.class.getName()
        );

        FakeManagedBattleContentClient.INSTANCE.contentId = null;
        assertTrue(lookup.contentId(UUID.randomUUID()).isEmpty());
        FakeManagedBattleContentClient.INSTANCE.contentId = "  ";
        assertTrue(lookup.contentId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void missingMccApiFailsDuringOptionalAdapterRegistration() {
        assertThrows(
            ReflectiveOperationException.class,
            () -> ReflectiveContentLookup.load(getClass().getClassLoader(), "missing.mcc.ClientApi")
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
