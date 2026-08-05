package com.github.postyizhan.betterquesting.api.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class NaiveLookupLogic<T> extends LookupLogic<T> {
    // fastutil and Trove are absent from the runtime classpath, and this build has no remapJar task
    // through which Loom could package either library as jar-in-jar; keep this cache on JDK collections.
    private Map<Integer, DBEntry<T>> backingMap;

    NaiveLookupLogic(SimpleDatabase<T> simpleDatabase) {
        super(simpleDatabase);
    }

    @Override
    public void onDataChange() {
        super.onDataChange();
        backingMap = null;
    }

    @Override
    public List<DBEntry<T>> bulkLookup(int[] keys) {
        if (backingMap == null) {
            // Upstream passes the expected element count to Trove, which divides by its load factor
            // internally. JDK HashMap treats the argument as table capacity and resizes once size
            // exceeds capacity * 0.75, so the count is pre-divided here to keep the single-allocation
            // behaviour of the upstream cache.
            backingMap = new HashMap<>((int) (simpleDatabase.mapDB.size() / 0.75f) + 1);
            for (DBEntry<T> entry : getRefCache()) {
                backingMap.put(entry.getID(), entry);
            }
        }
        List<DBEntry<T>> entries = new ArrayList<>(keys.length);
        for (int key : keys) {
            DBEntry<T> entry = backingMap.get(key);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
