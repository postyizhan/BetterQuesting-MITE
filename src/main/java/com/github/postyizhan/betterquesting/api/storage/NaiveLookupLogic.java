package com.github.postyizhan.betterquesting.api.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;

class NaiveLookupLogic<T> extends LookupLogic<T> {
    private Int2ObjectMap<DBEntry<T>> backingMap;

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
            backingMap = new Int2ObjectOpenHashMap<>(simpleDatabase.mapDB.size());
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
