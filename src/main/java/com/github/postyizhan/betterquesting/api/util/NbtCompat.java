package com.github.postyizhan.betterquesting.api.util;

import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Compatibility helpers for Forge-era NBT APIs absent or unsafe on MITE.
 * {@link #isNumeric} replaces {@code hasKey(key, 99)}; {@link #getTagId} replaces
 * {@code func_150299_b}; {@link #getListOrEmpty} replaces typed {@code getTagList}
 * and prevents MITE's wrong-type ClassCastException; {@link #getCompoundAt}
 * replaces {@code getCompoundTagAt} while rejecting non-compound elements.
 */
public final class NbtCompat {
    private NbtCompat() {
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
