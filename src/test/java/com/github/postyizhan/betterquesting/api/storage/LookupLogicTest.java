package com.github.postyizhan.betterquesting.api.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LookupLogicTest {
    private static final class TestDatabase<T> extends SimpleDatabase<T> {
    }

    private static final class TestBigDatabase<T> extends BigDatabase<T> {
    }

    @Test
    void denseArrayCacheAndSparseNaiveLookupReturnTheSameValues() {
        TestDatabase<String> dense = new TestDatabase<>();
        dense.add(0, "zero");
        dense.add(1, "one");
        dense.add(2, "two");

        TestDatabase<String> sparse = new TestDatabase<>();
        sparse.add(0, "zero");
        sparse.add(100, "one");
        sparse.add(200, "two");

        assertEquals(LookupLogicType.ArrayCache, LookupLogicType.determine(dense));
        assertEquals(LookupLogicType.Naive, LookupLogicType.determine(sparse));
        assertEquals(values(dense.bulkLookup(2, 0, 99, 1)), values(sparse.bulkLookup(200, 0, 99, 100)));
    }

    @Test
    void naiveLookupSkipsMissesPreservesOrderAndRebuildsAfterDataChange() {
        TestDatabase<String> database = new TestDatabase<>();
        database.add(1, "one");
        database.add(100, "hundred");

        assertEquals(LookupLogicType.Naive, LookupLogicType.determine(database));
        assertEquals(
            List.of("hundred", "one", "hundred"),
            values(database.bulkLookup(100, 50, 1, 100))
        );

        database.add(200, "two hundred");
        assertEquals(List.of("two hundred", "one"), values(database.bulkLookup(200, 1000, 1)));

        database.removeID(100);
        assertEquals(List.of("two hundred", "one"), values(database.bulkLookup(100, 200, 1)));
    }

    @Test
    void bigDatabasePreservesSparseQueryOrderAndDuplicates() {
        TestBigDatabase<String> database = new TestBigDatabase<>();
        database.add(1, "one");
        database.add(1_000_000, "far");

        assertEquals(List.of("far", "one", "far"), values(database.bulkLookup(1_000_000, 5, 1, 1_000_000)));
    }

    private static List<String> values(List<DBEntry<String>> entries) {
        return entries.stream().map(DBEntry::getValue).toList();
    }
}
