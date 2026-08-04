package com.github.postyizhan.betterquesting.api.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SimpleDatabaseTest {
    private static final class TestDatabase<T> extends SimpleDatabase<T> {
    }

    @Test
    void addsLooksUpAndRemovesEntries() {
        TestDatabase<String> database = new TestDatabase<>();
        String value = new String("alpha");
        DBEntry<String> entry = database.add(2, value);

        assertEquals(1, database.size());
        assertEquals(0, database.nextID());
        assertSame(value, database.getValue(2));
        assertEquals(2, database.getID(value));
        assertEquals(List.of(entry), database.getEntries());
        assertTrue(database.removeValue(value));
        assertFalse(database.removeID(2));
        assertNull(database.getValue(2));
        assertEquals(0, database.size());
    }

    @Test
    void valueLookupUsesIdentityLikeUpstream() {
        TestDatabase<String> database = new TestDatabase<>();
        String stored = new String("same");
        database.add(0, stored);

        assertEquals(-1, database.getID(new String("same")));
        assertEquals(0, database.getID(stored));
    }

    @Test
    void entryRetainsUpstreamValidationComparisonAndEquality() {
        DBEntry<String> first = new DBEntry<>(1, "value");
        DBEntry<String> equal = new DBEntry<>(1, "value");
        DBEntry<String> later = new DBEntry<>(2, "value");

        assertEquals(first, equal);
        assertTrue(first.compareTo(later) < 0);
        assertThrows(IllegalArgumentException.class, () -> new DBEntry<>(-1, "value"));
        assertThrows(NullPointerException.class, () -> new DBEntry<>(0, null));
    }

    @Test
    void bulkLookupSilentlySkipsIdsOutsideArrayCacheBounds() {
        TestDatabase<String> database = new TestDatabase<>();
        DBEntry<String> first = database.add(10, "first");
        DBEntry<String> last = database.add(11, "last");

        assertEquals(List.of(first, last), database.bulkLookup(9, -1, 10, 11, 12));
    }

    @Test
    void rejectsDuplicateIdsAndResetsAllocationState() {
        TestDatabase<String> database = new TestDatabase<>();
        database.add(0, "first");
        assertThrows(IllegalArgumentException.class, () -> database.add(0, "second"));
        database.reset();
        assertEquals(0, database.nextID());
        assertTrue(database.getEntries().isEmpty());
    }
}
