package com.github.postyizhan.betterquesting.api.storage;

public final class DBEntry<T> implements Comparable<DBEntry<T>> {
    private final int id;
    private final T value;

    public DBEntry(int id, T value) {
        if (id < 0) {
            throw new IllegalArgumentException("Entry ID cannot be negative");
        } else if (value == null) {
            throw new NullPointerException("Entry value cannot be null");
        }
        this.id = id;
        this.value = value;
    }

    public int getID() {
        return id;
    }

    public T getValue() {
        return value;
    }

    @Override
    public int compareTo(DBEntry<T> other) {
        return Integer.compare(id, other.id);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof DBEntry<?> entry)) {
            return false;
        }
        return id == entry.getID() && value.equals(entry.getValue());
    }
}
