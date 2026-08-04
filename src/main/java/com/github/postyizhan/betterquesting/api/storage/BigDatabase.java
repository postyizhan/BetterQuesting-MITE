package com.github.postyizhan.betterquesting.api.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Deprecated
public abstract class BigDatabase<T> extends SimpleDatabase<T> {
    public BigDatabase() {
    }

    @Deprecated
    public BigDatabase(int blockSize) {
    }

    @Override
    public List<DBEntry<T>> bulkLookup(int... ids) {
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        List<DBEntry<T>> values = new ArrayList<>();
        synchronized (this) {
            for (int id : ids) {
                T value = getValue(id);
                if (value != null) {
                    values.add(new DBEntry<>(id, value));
                }
            }
        }
        return values;
    }
}
