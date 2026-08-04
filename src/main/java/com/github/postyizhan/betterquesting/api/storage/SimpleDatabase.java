package com.github.postyizhan.betterquesting.api.storage;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public abstract class SimpleDatabase<T> implements IDatabase<T> {
    public static int CACHE_MAX_SIZE = 24 * 1024 * 1024 / 8;
    public static double SPARSE_RATIO = 0.15d;

    final TreeMap<Integer, T> mapDB = new TreeMap<>();

    private final BitSet idMap = new BitSet();
    private LookupLogicType type;
    private LookupLogic<T> logic;

    private LookupLogic<T> getLookupLogic() {
        if (type == null) {
            type = LookupLogicType.determine(this);
            logic = type.get(this);
        }
        return logic;
    }

    private void updateLookupLogic() {
        if (type == null) {
            return;
        }
        LookupLogicType newType = LookupLogicType.determine(this);
        if (newType != type) {
            type = null;
            logic = null;
        } else {
            logic.onDataChange();
        }
    }

    @Override
    public synchronized int nextID() {
        return idMap.nextClearBit(0);
    }

    @Override
    public synchronized DBEntry<T> add(int id, T value) {
        if (value == null) {
            throw new NullPointerException("Value cannot be null");
        } else if (id < 0) {
            throw new IllegalArgumentException("ID cannot be negative");
        } else if (mapDB.putIfAbsent(id, value) == null) {
            idMap.set(id);
            updateLookupLogic();
            return new DBEntry<>(id, value);
        }
        throw new IllegalArgumentException("ID or value is already contained within database");
    }

    @Override
    public synchronized boolean removeID(int key) {
        if (key < 0) {
            return false;
        }
        if (mapDB.remove(key) != null) {
            idMap.clear(key);
            updateLookupLogic();
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean removeValue(T value) {
        return value != null && removeID(getID(value));
    }

    @Override
    public synchronized int getID(T value) {
        if (value == null) {
            return -1;
        }
        for (DBEntry<T> entry : getEntries()) {
            if (entry.getValue() == value) {
                return entry.getID();
            }
        }
        return -1;
    }

    @Override
    public synchronized T getValue(int id) {
        if (id < 0 || mapDB.isEmpty()) {
            return null;
        }
        return mapDB.get(id);
    }

    @Override
    public synchronized int size() {
        return mapDB.size();
    }

    @Override
    public synchronized void reset() {
        mapDB.clear();
        idMap.clear();
        type = null;
        logic = null;
    }

    @Override
    public synchronized List<DBEntry<T>> getEntries() {
        return mapDB.isEmpty() ? Collections.emptyList() : getLookupLogic().getRefCache();
    }

    @Override
    public synchronized List<DBEntry<T>> bulkLookup(int... keys) {
        return mapDB.isEmpty() || keys.length == 0
            ? Collections.emptyList()
            : getLookupLogic().bulkLookup(keys);
    }
}
