package com.github.postyizhan.betterquesting.api.storage;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public interface IUuidDatabase<T> extends IBiMap<UUID, T> {
    UUID generateKey();

    UUID lookupKey(T value);

    Stream<Map.Entry<UUID, T>> orderedEntries();

    Stream<T> getAll(Collection<UUID> keys);

    Map<UUID, T> filterKeys(Collection<UUID> keys);

    IBiMap<UUID, T> filterValues(Collection<T> values);

    IBiMap<UUID, T> filterEntries(BiPredicate<UUID, T> filter);

    UUID removeValue(T value);

    @Override
    IBiMap<T, UUID> inverse();
}
