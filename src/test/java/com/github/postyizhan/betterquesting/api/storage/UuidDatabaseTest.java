package com.github.postyizhan.betterquesting.api.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidDatabaseTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void maintainsForwardAndReverseMappings() {
        UuidDatabase<String> database = new UuidDatabase<>();
        assertNull(database.put(FIRST, "first"));
        assertEquals("first", database.get(FIRST));
        assertEquals(FIRST, database.lookupKey("first"));
        assertEquals(FIRST, database.inverse().get("first"));

        assertEquals("first", database.remove(FIRST));
        assertFalse(database.containsValue("first"));
        assertNull(database.lookupKey("first"));
    }

    @Test
    void enforcesUniqueValuesAndForcePutRemovesTheOldKey() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "value");
        assertThrows(IllegalArgumentException.class, () -> database.put(SECOND, "value"));
        assertEquals("value", database.get(FIRST));
        assertFalse(database.containsKey(SECOND));
        assertEquals(FIRST, database.lookupKey("value"));

        database.forcePut(SECOND, "value");
        assertFalse(database.containsKey(FIRST));
        assertEquals("value", database.get(SECOND));
        assertEquals(SECOND, database.removeValue("value"));
        assertTrue(database.isEmpty());
    }

    @Test
    void iteratorRemovalKeepsReverseMappingSynchronized() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "value");
        Iterator<Map.Entry<UUID, String>> iterator = database.entrySet().iterator();
        iterator.next();
        iterator.remove();

        assertTrue(database.isEmpty());
        assertNull(database.lookupKey("value"));
    }

    @Test
    void retainsUpstreamNullKeyAndValueSupport() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(null, "null-key");
        database.put(FIRST, null);

        assertEquals("null-key", database.get(null));
        assertEquals(FIRST, database.lookupKey(null));
        assertNull(database.remove(FIRST));
        assertFalse(database.containsValue(null));
        assertEquals("null-key", database.remove(null));
    }

    @Test
    void supportsOrderedAndFilteredQueries() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(SECOND, "second");
        database.put(FIRST, "first");

        assertEquals(java.util.List.of("first", "second"), database.orderedEntries().map(Map.Entry::getValue).toList());
        assertEquals(Map.of(FIRST, "first"), database.filterKeys(java.util.List.of(FIRST, FIRST)));
        Map<UUID, String> valuesView = database.filterValues(java.util.List.of("second"));
        Map<UUID, String> entriesView = database.filterEntries((key, value) -> key.equals(FIRST));
        assertEquals(Map.of(SECOND, "second"), valuesView);
        assertEquals(Map.of(FIRST, "first"), entriesView);

        database.put(UUID.fromString("00000000-0000-0000-0000-000000000003"), "third");
        assertFalse(valuesView.containsValue("third"));
        entriesView.clear();
        assertFalse(database.containsKey(FIRST));
    }

    @Test
    void forcePutReturnsReplacedMappingWhenBothSidesConflict() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");

        assertEquals("first", database.forcePut(FIRST, "second"));
        assertEquals("second", database.get(FIRST));
        assertFalse(database.containsKey(SECOND));

        database.clear();
        database.put(FIRST, "first");
        database.put(SECOND, "second");
        assertEquals(SECOND, database.inverse().forcePut("second", FIRST));
        assertEquals("second", database.get(FIRST));
        assertFalse(database.containsKey(SECOND));
    }

    @Test
    void inverseIsALiveWritableView() {
        UuidDatabase<String> database = new UuidDatabase<>();
        IBiMap<String, UUID> inverse = database.inverse();
        inverse.put("first", FIRST);
        assertEquals("first", database.get(FIRST));
        assertEquals(FIRST, inverse.put("first", SECOND));
        assertFalse(database.containsKey(FIRST));
        assertEquals("first", database.get(SECOND));
        assertSame(database, inverse.inverse());

        assertEquals(SECOND, inverse.remove("first"));
        assertTrue(database.isEmpty());
    }

    @Test
    void forwardEntrySetValueMaintainsBothDirectionsAndRejectsConflicts() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");
        Map.Entry<UUID, String> entry = database.entrySet().stream()
            .filter(candidate -> FIRST.equals(candidate.getKey()))
            .findFirst()
            .orElseThrow();

        assertEquals("first", entry.setValue("replacement"));
        assertEquals("replacement", database.get(FIRST));
        assertEquals(FIRST, database.inverse().get("replacement"));
        assertFalse(database.inverse().containsKey("first"));
        assertThrows(IllegalArgumentException.class, () -> entry.setValue("second"));
        assertEquals("replacement", database.get(FIRST));
    }

    @Test
    void inverseEntrySetValueMaintainsBothDirections() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        Map.Entry<String, UUID> entry = database.inverse().entrySet().iterator().next();

        assertEquals(FIRST, entry.setValue(SECOND));
        assertFalse(database.containsKey(FIRST));
        assertEquals("first", database.get(SECOND));
        assertEquals(SECOND, database.inverse().get("first"));
    }

    @Test
    void filteredViewsAreWritableBidirectionalViews() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        IBiMap<UUID, String> filtered = database.filterEntries((key, value) -> !SECOND.equals(key));

        filtered.put(THIRD, "third");
        assertEquals(THIRD, filtered.inverse().get("third"));
        Map.Entry<UUID, String> entry = filtered.entrySet().stream()
            .filter(candidate -> FIRST.equals(candidate.getKey()))
            .findFirst()
            .orElseThrow();
        assertEquals("first", entry.setValue("replacement"));
        assertEquals(FIRST, database.inverse().get("replacement"));
        assertSame(filtered, filtered.inverse().inverse());
        assertThrows(IllegalArgumentException.class, () -> filtered.put(SECOND, "excluded"));
    }

    @Test
    void filteredViewIteratorsForbidRemovalAndEntriesRejectExcludedValues() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");
        IBiMap<UUID, String> filtered = database.filterEntries((key, value) -> !"excluded".equals(value));

        assertIteratorRemoveUnsupported(filtered.entrySet().iterator());
        assertIteratorRemoveUnsupported(filtered.keySet().iterator());
        assertIteratorRemoveUnsupported(filtered.values().iterator());
        assertIteratorRemoveUnsupported(filtered.inverse().entrySet().iterator());
        assertIteratorRemoveUnsupported(filtered.inverse().keySet().iterator());
        assertIteratorRemoveUnsupported(filtered.inverse().values().iterator());

        Map.Entry<UUID, String> entry = filtered.entrySet().iterator().next();
        assertThrows(IllegalArgumentException.class, () -> entry.setValue("excluded"));
        assertEquals(entry.getKey(), database.lookupKey(entry.getValue()));
    }

    @Test
    void filteredCollectionBulkOperationsOnlyRemoveMatchingMappings() {
        UuidDatabase<String> database = filteredFixture();
        IBiMap<UUID, String> filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        assertTrue(filtered.entrySet().removeAll(Set.of(Map.entry(FIRST, "first"), Map.entry(THIRD, "third"))));
        assertFalse(database.containsKey(FIRST));
        assertTrue(database.containsKey(THIRD));

        database = filteredFixture();
        filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        assertTrue(filtered.entrySet().retainAll(Set.of(Map.entry(FIRST, "first"), Map.entry(THIRD, "third"))));
        assertTrue(database.containsKey(FIRST));
        assertFalse(database.containsKey(SECOND));
        assertTrue(database.containsKey(THIRD));

        database = filteredFixture();
        filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        filtered.entrySet().clear();
        assertEquals(Map.of(THIRD, "third"), database);

        database = filteredFixture();
        filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        assertTrue(filtered.keySet().removeAll(Set.of(FIRST, THIRD)));
        assertFalse(database.containsKey(FIRST));
        assertTrue(database.containsKey(THIRD));

        database = filteredFixture();
        filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        assertTrue(filtered.values().retainAll(Set.of("first", "third")));
        assertTrue(database.containsKey(FIRST));
        assertFalse(database.containsKey(SECOND));
        assertTrue(database.containsKey(THIRD));

        database = filteredFixture();
        filtered = database.filterEntries((key, value) -> !THIRD.equals(key));
        filtered.values().clear();
        assertEquals(Map.of(THIRD, "third"), database);
    }

    @Test
    void filteredIteratorsRemainLazyViewsOfUnderlyingMaps() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        Iterator<Map.Entry<UUID, String>> forward = database.filterEntries((key, value) -> true).entrySet().iterator();
        database.put(SECOND, "second");
        assertThrows(ConcurrentModificationException.class, forward::hasNext);

        Iterator<Map.Entry<String, UUID>> inverse = database.filterEntries((key, value) -> true)
            .inverse().entrySet().iterator();
        database.put(THIRD, "third");
        assertThrows(ConcurrentModificationException.class, inverse::hasNext);
    }

    @Test
    void keyAndValueViewRemovalKeepsBothDirectionsSynchronized() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");

        assertTrue(database.keySet().remove(FIRST));
        assertFalse(database.inverse().containsKey("first"));

        Iterator<String> values = database.values().iterator();
        assertEquals("second", values.next());
        values.remove();
        assertFalse(database.containsKey(SECOND));
        assertTrue(database.isEmpty());
    }

    @Test
    void inverseKeyAndValueViewRemovalKeepsBothDirectionsSynchronized() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");
        IBiMap<String, UUID> inverse = database.inverse();

        assertTrue(inverse.keySet().remove("first"));
        assertFalse(database.containsKey(FIRST));

        Iterator<UUID> keys = inverse.values().iterator();
        assertEquals(SECOND, keys.next());
        keys.remove();
        assertFalse(inverse.containsKey("second"));
        assertTrue(database.isEmpty());
    }

    private static UuidDatabase<String> filteredFixture() {
        UuidDatabase<String> database = new UuidDatabase<>();
        database.put(FIRST, "first");
        database.put(SECOND, "second");
        database.put(THIRD, "third");
        return database;
    }

    private static void assertIteratorRemoveUnsupported(Iterator<?> iterator) {
        assertTrue(iterator.hasNext());
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }
}
