package com.github.postyizhan.betterquesting.api.util;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;

class NbtCompatTest {
    @Test
    void numericRequiresPresentNumericTag() {
        NBTTagCompound tag = new NBTTagCompound();
        assertFalse(NbtCompat.isNumeric(tag, "value"));
        tag.setString("value", "1");
        assertFalse(NbtCompat.isNumeric(tag, "value"));
        tag.setInteger("value", 1);
        assertTrue(NbtCompat.isNumeric(tag, "value"));
    }

    @Test
    void tagIdIsZeroWhenMissing() {
        NBTTagCompound tag = new NBTTagCompound();
        assertEquals(0, NbtCompat.getTagId(tag, "missing"));
        tag.setString("value", "x");
        assertEquals(8, NbtCompat.getTagId(tag, "value"));
    }

    @Test
    void listOrEmptyNeverCastsWrongType() {
        NBTTagCompound tag = new NBTTagCompound();
        assertEquals(0, NbtCompat.getListOrEmpty(tag, "items").tagCount());
        tag.setString("items", "not-list");
        assertDoesNotThrow(() -> NbtCompat.getListOrEmpty(tag, "items"));
        assertEquals(0, NbtCompat.getListOrEmpty(tag, "items").tagCount());
        NBTTagList list = new NBTTagList("");
        list.appendTag(new NBTTagString("", "x"));
        tag.setTag("items", list);
        assertSame(list, NbtCompat.getListOrEmpty(tag, "items"));
    }

    @Test
    void compoundAtRejectsOtherElementTypes() {
        NBTTagList compounds = new NBTTagList("");
        NBTTagCompound compound = new NBTTagCompound("");
        compounds.appendTag(compound);
        assertSame(compound, NbtCompat.getCompoundAt(compounds, 0));
        NBTTagList ints = new NBTTagList("");
        ints.appendTag(new NBTTagInt("", 2));
        assertNull(NbtCompat.getCompoundAt(ints, 0));
    }
}
