package jbro.minecraft.roundingblock.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RoundingBlockConfigDraftTest {
    @Test
    void openingAndResettingTheDraftPreservesTheCompleteConfig() {
        var initial = RoundingBlockConfig.defaults().withRadius(0.15).withEnabled(false);
        var draft = new RoundingBlockConfigDraft(initial);
        assertEquals(initial, draft.build());
        draft.reset(RoundingBlockConfig.defaults());
        assertEquals(RoundingBlockConfig.defaults(), draft.build());
        assertEquals(0.15, initial.quality().radius());
    }

    @Test
    void invalidTextIsRetainedButCannotBecomeAnActiveConfig() {
        var draft = new RoundingBlockConfigDraft(RoundingBlockConfig.defaults());
        for (String value : new String[] {"", "NaN", "Infinity", "0.22", "0", "oops"}) {
            draft.set("radius", value);
            assertThrows(IllegalArgumentException.class, draft::build);
            assertEquals(value, draft.get("radius"));
        }
        draft.set("radius", "0.125");
        for (String value : new String[] {"", "0", "9", "3.5", "oops"}) {
            draft.set("segments", value);
            assertThrows(IllegalArgumentException.class, draft::build);
        }
    }

    @Test
    void allCacheLimitsAreCheckedBeforeSaving() {
        var draft = new RoundingBlockConfigDraft(RoundingBlockConfig.defaults());
        for (String key : new String[] {"fullBlockPlans", "slabPlans", "complexShapePlans", "fluidContactPlans"}) {
            draft.set(key, "15");
            assertThrows(IllegalArgumentException.class, draft::build);
            draft.set(key, "4097");
            assertThrows(IllegalArgumentException.class, draft::build);
            draft.set(key, "4096");
            draft.build();
        }
        draft.set("weightedModelVariants", "257");
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.set("weightedModelVariants", "0");
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.set("weightedModelVariants", "1");
        draft.set("radius", "0.015625");
        draft.set("segments", "8");
        assertEquals(8, draft.build().quality().segments());
        assertEquals(1, draft.build().cache().weightedModelVariants());
    }
}
