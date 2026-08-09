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
    void sortedKeysAreAscendingAndSnapshotTheLiveTagView() {
        NBTTagCompound tag = new NBTTagCompound();
        assertEquals(java.util.List.of(), NbtCompat.sortedKeys(tag));
        assertEquals(java.util.List.of(), NbtCompat.sortedKeys(null));

        tag.setInteger("zebra", 1);
        tag.setInteger("alpha", 2);
        tag.setInteger("mid", 3);
        assertEquals(java.util.List.of("alpha", "mid", "zebra"), NbtCompat.sortedKeys(tag));

        // getTags() is a live view over tagMap.values(), so removal during iteration would throw
        // without the snapshot.
        assertDoesNotThrow(() -> {
            for (String key : NbtCompat.sortedKeys(tag)) {
                tag.removeTag(key);
            }
        });
        assertTrue(tag.hasNoTags());
    }

    @Test
    void elementsCopyListContentsInOrder() {
        assertEquals(java.util.List.of(), NbtCompat.elements(null));

        NBTTagList list = new NBTTagList("");
        assertEquals(java.util.List.of(), NbtCompat.elements(list));

        NBTTagInt first = new NBTTagInt("", 1);
        NBTTagString second = new NBTTagString("", "x");
        list.appendTag(first);
        list.appendTag(second);
        assertEquals(java.util.List.of(first, second), NbtCompat.elements(list));
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
