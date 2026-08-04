package com.github.postyizhan.betterquesting.api.storage;

import java.util.Map;
import java.util.Set;

/**
 * Minimal bidirectional map contract used in place of Guava's BiMap.
 * Values are unique and are therefore exposed as a set-backed live view.
 */
public interface IBiMap<K, V> extends Map<K, V> {
    V forcePut(K key, V value);

    IBiMap<V, K> inverse();

    @Override
    Set<V> values();
}
