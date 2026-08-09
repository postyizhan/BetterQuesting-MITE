package com.github.postyizhan.betterquesting.api.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Compatibility helpers for Forge-era NBT APIs absent or unsafe on MITE.
 * {@link #isNumeric} replaces {@code hasKey(key, 99)}; {@link #getTagId} replaces
 * {@code func_150299_b}; {@link #getListOrEmpty} replaces typed {@code getTagList}
 * and prevents MITE's wrong-type ClassCastException; {@link #getCompoundAt}
 * replaces {@code getCompoundTagAt} while rejecting non-compound elements;
 * {@link #sortedKeys} replaces {@code func_150296_c}; {@link #elements} replaces
 * upstream's reflective {@code NBTTagList.tagList} field access.
 */
public final class NbtCompat {
    private NbtCompat() {
    }

    /**
     * Returns this compound's keys in ascending order.
     *
     * <p>MITE's {@code NBTTagCompound} exposes no key set; the only enumeration path is
     * {@code getTags()} plus {@link NBTBase#getName()} per element. {@code getTags()} is a live view
     * over {@code tagMap.values()}, so the names are copied into a fresh {@link TreeSet} before
     * returning and the caller may mutate the compound while iterating the result.
     *
     * <p>Ordering matches upstream {@code NBTConverter.NBTtoJSON_Compound}, which sorts through a
     * {@code TreeSet} so serialized output is byte-stable.
     */
    public static List<String> sortedKeys(NBTTagCompound nbt) {
        if (nbt == null) {
            return List.of();
        }
        Collection<?> tags = nbt.getTags();
        TreeSet<String> sorted = new TreeSet<>();
        for (Object tag : tags) {
            sorted.add(((NBTBase) tag).getName());
        }
        return List.copyOf(sorted);
    }

    /**
     * Returns this list's elements in order.
     *
     * <p>Upstream reached the backing {@code ArrayList} reflectively because 1.7.10 offered no
     * accessor; MITE exposes public {@code tagCount()} and {@code tagAt(int)}, so this copies
     * through those instead of depending on an obfuscation-sensitive field name.
     */
    public static List<NBTBase> elements(NBTTagList list) {
        if (list == null) {
            return List.of();
        }
        int count = list.tagCount();
        List<NBTBase> elements = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            elements.add(list.tagAt(index));
        }
        return elements;
    }

    public static boolean isNumeric(NBTTagCompound nbt, String key) {
        int id = getTagId(nbt, key);
        return id >= 1 && id <= 6;
    }

    public static int getTagId(NBTTagCompound nbt, String key) {
        return nbt.hasKey(key) ? nbt.getTag(key).getId() : 0;
    }

    public static NBTTagList getListOrEmpty(NBTTagCompound nbt, String key) {
        return getTagId(nbt, key) == 9 ? nbt.getTagList(key) : new NBTTagList("");
    }

    public static NBTTagCompound getCompoundAt(NBTTagList list, int index) {
        NBTBase tag = list.tagAt(index);
        return tag.getId() == 10 ? (NBTTagCompound) tag : null;
    }
}
