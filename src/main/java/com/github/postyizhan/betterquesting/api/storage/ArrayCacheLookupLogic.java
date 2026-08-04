package com.github.postyizhan.betterquesting.api.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

class ArrayCacheLookupLogic<T> extends LookupLogic<T> {
    private DBEntry<T>[] cache;
    private int offset = -1;

    ArrayCacheLookupLogic(SimpleDatabase<T> simpleDatabase) {
        super(simpleDatabase);
    }

    @Override
    public void onDataChange() {
        super.onDataChange();
        cache = null;
        offset = -1;
    }

    @Override
    public List<DBEntry<T>> getRefCache() {
        if (refCache != null) {
            return refCache;
        }
        if (cache == null) {
            return super.getRefCache();
        }
        refCache = Arrays.stream(cache)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        return refCache;
    }

    @Override
    public List<DBEntry<T>> bulkLookup(int[] keys) {
        computeCache();
        List<DBEntry<T>> entries = new ArrayList<>(keys.length);
        for (int key : keys) {
            int cacheIndex = key - offset;
            // Bulk lookup mirrors getValue: IDs outside the allocated range are absent, not indexing errors.
            if (cacheIndex < 0 || cacheIndex >= cache.length) {
                continue;
            }
            DBEntry<T> entry = cache[cacheIndex];
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private void computeCache() {
        if (cache != null) {
            return;
        }
        cache = new DBEntry[simpleDatabase.mapDB.lastKey() - simpleDatabase.mapDB.firstKey() + 1];
        offset = simpleDatabase.mapDB.firstKey();
        if (refCache == null) {
            for (Map.Entry<Integer, T> entry : simpleDatabase.mapDB.entrySet()) {
                cache[entry.getKey() - offset] = new DBEntry<>(entry.getKey(), entry.getValue());
            }
        } else {
            for (DBEntry<T> entry : refCache) {
                cache[entry.getID() - offset] = entry;
            }
        }
    }
}
