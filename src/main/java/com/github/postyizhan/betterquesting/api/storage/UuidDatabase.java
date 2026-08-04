package com.github.postyizhan.betterquesting.api.storage;

import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public class UuidDatabase<T> extends AbstractMap<UUID, T> implements IUuidDatabase<T> {
    private final Map<UUID, T> database = new HashMap<>();
    private final Map<T, UUID> reverseDatabase = new HashMap<>();
    private final IBiMap<T, UUID> inverseView = new InverseView();

    private int compareEntries(Map.Entry<UUID, T> first, Map.Entry<UUID, T> second) {
        return UuidConverter.encodeUuid(first.getKey()).compareTo(UuidConverter.encodeUuid(second.getKey()));
    }

    @Override
    public UUID generateKey() {
        UUID newKey;
        do {
            newKey = UUID.randomUUID();
        } while (containsKey(newKey));
        return newKey;
    }

    @Override
    public UUID lookupKey(T value) {
        return reverseDatabase.get(value);
    }

    @Override
    public Stream<Map.Entry<UUID, T>> orderedEntries() {
        return entrySet().stream().sorted(this::compareEntries);
    }

    @Override
    public Stream<T> getAll(Collection<UUID> keys) {
        return keys.stream().distinct().filter(database::containsKey).map(database::get);
    }

    @Override
    public Map<UUID, T> filterKeys(Collection<UUID> keys) {
        Map<UUID, T> result = new LinkedHashMap<>();
        keys.stream().distinct().filter(database::containsKey).forEach(key -> result.put(key, database.get(key)));
        return result;
    }

    @Override
    public IBiMap<UUID, T> filterValues(Collection<T> values) {
        return filteredView((key, value) -> values.contains(value));
    }

    @Override
    public IBiMap<UUID, T> filterEntries(BiPredicate<UUID, T> filter) {
        return filteredView(filter);
    }

    private IBiMap<UUID, T> filteredView(BiPredicate<UUID, T> filter) {
        return new FilteredView(filter);
    }

    @Override
    public UUID removeValue(T value) {
        return inverseView.remove(value);
    }

    @Override
    public T put(UUID key, T value) {
        UUID existingKey = reverseDatabase.get(value);
        if (reverseDatabase.containsKey(value) && !Objects.equals(existingKey, key)) {
            throw new IllegalArgumentException("value already present: " + value);
        }
        boolean keyPresent = database.containsKey(key);
        T previous = database.put(key, value);
        if (keyPresent && !Objects.equals(previous, value)) {
            reverseDatabase.remove(previous);
        }
        reverseDatabase.put(value, key);
        return previous;
    }

    @Override
    public T forcePut(UUID key, T value) {
        UUID conflictingKey = reverseDatabase.get(value);
        if (reverseDatabase.containsKey(value) && !Objects.equals(conflictingKey, key)) {
            database.remove(conflictingKey);
            reverseDatabase.remove(value);
        }
        return put(key, value);
    }

    @Override
    public T remove(Object key) {
        if (!database.containsKey(key)) {
            return null;
        }
        T removed = database.remove(key);
        reverseDatabase.remove(removed);
        return removed;
    }

    @Override
    public void clear() {
        database.clear();
        reverseDatabase.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return database.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return reverseDatabase.containsKey(value);
    }

    @Override
    public T get(Object key) {
        return database.get(key);
    }

    @Override
    public int size() {
        return database.size();
    }

    @Override
    public Set<T> values() {
        return inverseView.keySet();
    }

    @Override
    public IBiMap<T, UUID> inverse() {
        return inverseView;
    }

    @Override
    public Set<Map.Entry<UUID, T>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<UUID, T>> iterator() {
                Iterator<Map.Entry<UUID, T>> iterator = database.entrySet().iterator();
                return new Iterator<>() {
                    private UUID currentKey;
                    private T currentValue;
                    private boolean canRemove;

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Map.Entry<UUID, T> next() {
                        Map.Entry<UUID, T> current = iterator.next();
                        currentKey = current.getKey();
                        currentValue = current.getValue();
                        canRemove = true;
                        return new Map.Entry<>() {
                            private final UUID key = currentKey;

                            @Override
                            public UUID getKey() {
                                return key;
                            }

                            @Override
                            public T getValue() {
                                return database.get(key);
                            }

                            @Override
                            public T setValue(T value) {
                                T previous = UuidDatabase.this.put(key, value);
                                currentValue = value;
                                return previous;
                            }

                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Map.Entry<?, ?> entry
                                    && Objects.equals(key, entry.getKey())
                                    && Objects.equals(getValue(), entry.getValue());
                            }

                            @Override
                            public int hashCode() {
                                return Objects.hashCode(key) ^ Objects.hashCode(getValue());
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        if (!canRemove) {
                            throw new IllegalStateException();
                        }
                        iterator.remove();
                        reverseDatabase.remove(currentValue);
                        canRemove = false;
                    }
                };
            }

            @Override
            public int size() {
                return database.size();
            }

            @Override
            public void clear() {
                UuidDatabase.this.clear();
            }
        };
    }

    private final class InverseView extends AbstractMap<T, UUID> implements IBiMap<T, UUID> {
        @Override
        public UUID put(T value, UUID key) {
            if (database.containsKey(key) && !Objects.equals(database.get(key), value)) {
                throw new IllegalArgumentException("value already present: " + key);
            }
            boolean valuePresent = reverseDatabase.containsKey(value);
            UUID previous = reverseDatabase.get(value);
            if (valuePresent && !Objects.equals(previous, key)) {
                database.remove(previous);
            }
            database.put(key, value);
            reverseDatabase.put(value, key);
            return valuePresent ? previous : null;
        }

        @Override
        public UUID forcePut(T value, UUID key) {
            boolean valuePresent = reverseDatabase.containsKey(value);
            UUID previous = reverseDatabase.get(value);
            UuidDatabase.this.forcePut(key, value);
            return valuePresent ? previous : null;
        }

        @Override
        public UUID get(Object value) {
            return reverseDatabase.get(value);
        }

        @Override
        public boolean containsKey(Object value) {
            return reverseDatabase.containsKey(value);
        }

        @Override
        public UUID remove(Object value) {
            if (!reverseDatabase.containsKey(value)) {
                return null;
            }
            UUID key = reverseDatabase.get(value);
            UuidDatabase.this.remove(key);
            return key;
        }

        @Override
        public void clear() {
            UuidDatabase.this.clear();
        }

        @Override
        public Set<UUID> values() {
            return UuidDatabase.this.keySet();
        }

        @Override
        public IBiMap<UUID, T> inverse() {
            return UuidDatabase.this;
        }

        @Override
        public Set<Entry<T, UUID>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<T, UUID>> iterator() {
                    Iterator<Entry<T, UUID>> iterator = reverseDatabase.entrySet().iterator();
                    return new Iterator<>() {
                        private T currentValue;
                        private UUID currentKey;
                        private boolean canRemove;

                        @Override
                        public boolean hasNext() {
                            return iterator.hasNext();
                        }

                        @Override
                        public Entry<T, UUID> next() {
                            Entry<T, UUID> current = iterator.next();
                            currentValue = current.getKey();
                            currentKey = current.getValue();
                            canRemove = true;
                            return new Map.Entry<>() {
                                private final T value = currentValue;

                                @Override
                                public T getKey() {
                                    return value;
                                }

                                @Override
                                public UUID getValue() {
                                    return reverseDatabase.get(value);
                                }

                                @Override
                                public UUID setValue(UUID key) {
                                    UUID previous = inverseView.put(value, key);
                                    currentKey = key;
                                    return previous;
                                }

                                @Override
                                public boolean equals(Object other) {
                                    return other instanceof Map.Entry<?, ?> entry
                                        && Objects.equals(value, entry.getKey())
                                        && Objects.equals(getValue(), entry.getValue());
                                }

                                @Override
                                public int hashCode() {
                                    return Objects.hashCode(value) ^ Objects.hashCode(getValue());
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!canRemove) {
                                throw new IllegalStateException();
                            }
                            iterator.remove();
                            database.remove(currentKey);
                            canRemove = false;
                        }
                    };
                }

                @Override
                public int size() {
                    return reverseDatabase.size();
                }

                @Override
                public void clear() {
                    UuidDatabase.this.clear();
                }
            };
        }
    }

    private final class FilteredView extends AbstractMap<UUID, T> implements IBiMap<UUID, T> {
        private final BiPredicate<UUID, T> filter;
        private final IBiMap<T, UUID> inverse;

        private FilteredView(BiPredicate<UUID, T> filter) {
            this.filter = filter;
            this.inverse = new FilteredInverseView(this);
        }

        private void requireMatch(UUID key, T value) {
            if (!filter.test(key, value)) {
                throw new IllegalArgumentException("Entry does not match filter");
            }
        }

        @Override
        public T put(UUID key, T value) {
            requireMatch(key, value);
            return UuidDatabase.this.put(key, value);
        }

        @Override
        public T forcePut(UUID key, T value) {
            requireMatch(key, value);
            return UuidDatabase.this.forcePut(key, value);
        }

        @Override
        public T get(Object key) {
            if (!database.containsKey(key)) {
                return null;
            }
            T value = database.get(key);
            return filter.test((UUID) key, value) ? value : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return database.containsKey(key) && filter.test((UUID) key, database.get(key));
        }

        @Override
        public T remove(Object key) {
            return containsKey(key) ? UuidDatabase.this.remove(key) : null;
        }

        @Override
        public void clear() {
            entrySet().clear();
        }

        @Override
        public Set<T> values() {
            return inverse.keySet();
        }

        @Override
        public IBiMap<T, UUID> inverse() {
            return inverse;
        }

        @Override
        public Set<Entry<UUID, T>> entrySet() {
            return filteredEntrySet(filter);
        }
    }

    private final class FilteredInverseView extends AbstractMap<T, UUID> implements IBiMap<T, UUID> {
        private final FilteredView forward;

        private FilteredInverseView(FilteredView forward) {
            this.forward = forward;
        }

        @Override
        public UUID put(T value, UUID key) {
            forward.requireMatch(key, value);
            return inverseView.put(value, key);
        }

        @Override
        public UUID forcePut(T value, UUID key) {
            forward.requireMatch(key, value);
            return inverseView.forcePut(value, key);
        }

        @Override
        public UUID get(Object value) {
            if (!reverseDatabase.containsKey(value)) {
                return null;
            }
            UUID key = reverseDatabase.get(value);
            return forward.filter.test(key, database.get(key)) ? key : null;
        }

        @Override
        public boolean containsKey(Object value) {
            return reverseDatabase.containsKey(value)
                && forward.filter.test(reverseDatabase.get(value), database.get(reverseDatabase.get(value)));
        }

        @Override
        public UUID remove(Object value) {
            return containsKey(value) ? inverseView.remove(value) : null;
        }

        @Override
        public void clear() {
            forward.clear();
        }

        @Override
        public Set<UUID> values() {
            return forward.keySet();
        }

        @Override
        public IBiMap<UUID, T> inverse() {
            return forward;
        }

        @Override
        public Set<Entry<T, UUID>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<T, UUID>> iterator() {
                    Iterator<Entry<UUID, T>> forwardIterator = forward.entrySet().iterator();
                    return new Iterator<>() {
                        private Entry<UUID, T> current;
                        private UUID currentKey;
                        private boolean canRemove;

                        @Override
                        public boolean hasNext() {
                            return forwardIterator.hasNext();
                        }

                        @Override
                        public Entry<T, UUID> next() {
                            current = forwardIterator.next();
                            currentKey = current.getKey();
                            canRemove = true;
                            T value = current.getValue();
                            return new Map.Entry<>() {
                                @Override
                                public T getKey() {
                                    return value;
                                }

                                @Override
                                public UUID getValue() {
                                    return reverseDatabase.get(value);
                                }

                                @Override
                                public UUID setValue(UUID key) {
                                    UUID previous = FilteredInverseView.this.put(value, key);
                                    currentKey = key;
                                    return previous;
                                }

                                @Override
                                public boolean equals(Object other) {
                                    return other instanceof Map.Entry<?, ?> entry
                                        && Objects.equals(value, entry.getKey())
                                        && Objects.equals(getValue(), entry.getValue());
                                }

                                @Override
                                public int hashCode() {
                                    return Objects.hashCode(value) ^ Objects.hashCode(getValue());
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!canRemove) {
                                throw new IllegalStateException();
                            }
                            UuidDatabase.this.remove(currentKey);
                            canRemove = false;
                        }
                    };
                }

                @Override
                public int size() {
                    return forward.size();
                }

                @Override
                public void clear() {
                    forward.clear();
                }
            };
        }
    }

    private Set<Map.Entry<UUID, T>> filteredEntrySet(BiPredicate<UUID, T> filter) {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<UUID, T>> iterator() {
                Iterator<UUID> keys = database.entrySet().stream()
                    .filter(entry -> filter.test(entry.getKey(), entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList()
                    .iterator();
                return new Iterator<>() {
                    private UUID currentKey;
                    private boolean canRemove;

                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Map.Entry<UUID, T> next() {
                        currentKey = keys.next();
                        canRemove = true;
                        return new Map.Entry<>() {
                            private final UUID key = currentKey;

                            @Override
                            public UUID getKey() {
                                return key;
                            }

                            @Override
                            public T getValue() {
                                return database.get(key);
                            }

                            @Override
                            public T setValue(T value) {
                                if (!filter.test(key, value)) {
                                    throw new IllegalArgumentException("Entry does not match filter");
                                }
                                return UuidDatabase.this.put(key, value);
                            }

                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Map.Entry<?, ?> entry
                                    && Objects.equals(key, entry.getKey())
                                    && Objects.equals(getValue(), entry.getValue());
                            }

                            @Override
                            public int hashCode() {
                                return Objects.hashCode(key) ^ Objects.hashCode(getValue());
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        if (!canRemove) {
                            throw new IllegalStateException();
                        }
                        UuidDatabase.this.remove(currentKey);
                        canRemove = false;
                    }
                };
            }

            @Override
            public int size() {
                int size = 0;
                for (Map.Entry<UUID, T> entry : database.entrySet()) {
                    if (filter.test(entry.getKey(), entry.getValue())) {
                        size++;
                    }
                }
                return size;
            }

            @Override
            public void clear() {
                Iterator<Map.Entry<UUID, T>> iterator = iterator();
                while (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        };
    }
}
