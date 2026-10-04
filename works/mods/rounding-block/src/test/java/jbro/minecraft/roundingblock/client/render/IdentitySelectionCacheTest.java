package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class IdentitySelectionCacheTest {
    @Test
    void matchesEquivalentGroupsOnlyWhenElementsAreTheSameObjects() {
        IdentitySelectionCache<Object, String> cache = new IdentitySelectionCache<>(2);
        Object first = new Object();
        Object second = new Object();
        List<Object>[] selection = groups(List.of(first), List.of(second));

        assertNull(cache.find(selection));
        assertEquals("variant", cache.putIfAbsent(selection, "variant"));
        assertEquals("variant", cache.find(groups(List.of(first), List.of(second))));
        assertNull(cache.find(groups(List.of(new Object()), List.of(second))));
    }

    @Test
    void staysBoundedAndAllowsRecentSelectionsToReplaceOldOnes() {
        IdentitySelectionCache<Object, Object> cache = new IdentitySelectionCache<>(2);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        Object firstValue = new Object();
        Object thirdValue = new Object();

        cache.putIfAbsent(groups(List.of(first)), firstValue);
        cache.putIfAbsent(groups(List.of(second)), new Object());
        cache.putIfAbsent(groups(List.of(third)), thirdValue);

        assertEquals(2, cache.size());
        assertNull(cache.find(groups(List.of(first))));
        assertSame(thirdValue, cache.find(groups(List.of(third))));
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static <T> List<T>[] groups(List<T>... groups) {
        return groups;
    }
}
