package com.github.postyizhan.betterquesting.api.storage;

import java.util.Collections;
import java.util.List;

class EmptyLookupLogic<T> extends LookupLogic<T> {
    EmptyLookupLogic(SimpleDatabase<T> simpleDatabase) {
        super(simpleDatabase);
    }

    @Override
    public List<DBEntry<T>> bulkLookup(int[] keys) {
        return Collections.emptyList();
    }
}
