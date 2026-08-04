package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class QuestLineEntryTest {
    @Test
    @SuppressWarnings("deprecation")
    void constructorsAndMutatorsPreserveDimensions() {
        QuestLineEntry standard = new QuestLineEntry(3, 4);
        assertEntry(standard, 3, 4, 24, 24);

        QuestLineEntry square = new QuestLineEntry(1, 2, 9);
        assertEntry(square, 1, 2, 9, 9);
        assertEquals(9, square.getSize());

        QuestLineEntry rectangle = new QuestLineEntry(5, 6, 7, 8);
        assertEntry(rectangle, 5, 6, 7, 8);
        rectangle.setPosition(-1, -2);
        rectangle.setSize(10, 11);
        assertEntry(rectangle, -1, -2, 10, 11);
        rectangle.setSize(12);
        assertEntry(rectangle, -1, -2, 12, 12);
    }

    @Test
    void readsLegacyAndCurrentFormatsAndWritesExactFields() {
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("size", 13);
        legacy.setInteger("x", 2);
        legacy.setInteger("y", 3);
        assertEntry(new QuestLineEntry(legacy), 2, 3, 13, 13);

        QuestLineEntry source = new QuestLineEntry(7, 8, 20, 21);
        NBTTagCompound serialized = source.writeToNBT(new NBTTagCompound());
        assertTrue(serialized.hasKey("sizeX"));
        assertTrue(serialized.hasKey("sizeY"));
        assertTrue(serialized.hasKey("x"));
        assertTrue(serialized.hasKey("y"));
        assertEntry(new QuestLineEntry(serialized), 7, 8, 20, 21);
    }

    private static void assertEntry(QuestLineEntry entry, int x, int y, int sizeX, int sizeY) {
        assertEquals(x, entry.getPosX());
        assertEquals(y, entry.getPosY());
        assertEquals(sizeX, entry.getSizeX());
        assertEquals(sizeY, entry.getSizeY());
    }
}
