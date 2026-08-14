package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.storage.INameCache;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public final class NameCache implements INameCache {
    public static final NameCache INSTANCE = new NameCache();

    private final Map<UUID, Entry> cache = new LinkedHashMap<>();
    private List<String> allNames;

    @Override
    public synchronized boolean updateName(UUID uuid, String name, boolean operator) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Entry previous = cache.get(uuid);
        if (previous != null && previous.name.equals(name) && previous.operator == operator) {
            return false;
        }
        cache.put(uuid, new Entry(name, operator));
        allNames = null;
        return true;
    }

    @Override
    public synchronized String getName(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Entry entry = cache.get(uuid);
        return entry == null ? uuid.toString() : entry.name;
    }

    @Override
    public synchronized UUID getUUID(String name) {
        Objects.requireNonNull(name, "name");
        UUID match = null;
        for (Map.Entry<UUID, Entry> entry : cache.entrySet()) {
            if (!entry.getValue().name.equalsIgnoreCase(name)) continue;
            if (match != null && !match.equals(entry.getKey())) return null;
            match = entry.getKey();
        }
        return match;
    }

    @Override
    public synchronized List<String> getAllNames() {
        if (allNames == null) {
            List<String> names = new ArrayList<>(cache.size());
            for (Entry entry : cache.values()) names.add(entry.name);
            allNames = Collections.unmodifiableList(names);
        }
        return allNames;
    }

    @Override
    public synchronized boolean isOP(UUID uuid) {
        Entry entry = cache.get(Objects.requireNonNull(uuid, "uuid"));
        return entry != null && entry.operator;
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    @Override
    public synchronized NBTTagList writeToNBT(NBTTagList nbt, List<UUID> users) {
        List<Map.Entry<UUID, Entry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
        for (Map.Entry<UUID, Entry> entry : entries) {
            if (users != null && !users.contains(entry.getKey())) continue;
            NBTTagCompound serialized = new NBTTagCompound();
            serialized.setString("uuid", entry.getKey().toString());
            serialized.setString("name", entry.getValue().name);
            serialized.setBoolean("isOP", entry.getValue().operator);
            nbt.appendTag(serialized);
        }
        return nbt;
    }

    @Override
    public synchronized void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) cache.clear();
        for (int index = 0; index < nbt.tagCount(); index++) {
            NBTTagCompound serialized = NbtCompat.getCompoundAt(nbt, index);
            if (serialized == null) continue;
            try {
                UUID uuid = UUID.fromString(serialized.getString("uuid"));
                cache.put(uuid, new Entry(serialized.getString("name"), serialized.getBoolean("isOP")));
            } catch (RuntimeException ignored) {
            }
        }
        allNames = null;
    }

    @Override
    public synchronized void reset() {
        cache.clear();
        allNames = null;
    }

    private static final class Entry {
        private final String name;
        private final boolean operator;

        private Entry(String name, boolean operator) {
            this.name = name;
            this.operator = operator;
        }
    }
}
