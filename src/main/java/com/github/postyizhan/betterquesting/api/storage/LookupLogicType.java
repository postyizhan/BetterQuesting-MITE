package com.github.postyizhan.betterquesting.api.storage;

import java.util.function.Function;
import java.util.function.Predicate;

import static com.github.postyizhan.betterquesting.api.storage.SimpleDatabase.CACHE_MAX_SIZE;
import static com.github.postyizhan.betterquesting.api.storage.SimpleDatabase.SPARSE_RATIO;

enum LookupLogicType {
    Empty(database -> database.mapDB.isEmpty(), EmptyLookupLogic::new),
    ArrayCache(database -> database.mapDB.size() < CACHE_MAX_SIZE
        && database.mapDB.size() > SPARSE_RATIO * (database.mapDB.lastKey() - database.mapDB.firstKey()),
        ArrayCacheLookupLogic::new),
    Naive(database -> true, NaiveLookupLogic::new);

    private final Predicate<SimpleDatabase<?>> shouldUse;
    private final Function<SimpleDatabase<?>, LookupLogic<?>> factory;

    LookupLogicType(Predicate<SimpleDatabase<?>> shouldUse, Function<SimpleDatabase<?>, LookupLogic<?>> factory) {
        this.shouldUse = shouldUse;
        this.factory = factory;
    }

    static LookupLogicType determine(SimpleDatabase<?> database) {
        for (LookupLogicType type : values()) {
            if (type.shouldUse.test(database)) {
                return type;
            }
        }
        return Naive;
    }

    @SuppressWarnings("unchecked")
    <T> LookupLogic<T> get(SimpleDatabase<T> database) {
        return (LookupLogic<T>) factory.apply(database);
    }
}
