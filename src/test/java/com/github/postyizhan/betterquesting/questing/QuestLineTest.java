package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineEntry;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class QuestLineTest {
    private static final UUID ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void createsEntriesAndUsesLeftClosedRightOpenHitBoxes() {
        QuestLine line = new QuestLine();
        IQuestLineEntry created = line.createNew(ID);
        assertSame(created, line.get(ID));
        created.setPosition(10, 20);
        created.setSize(3, 4);
        assertSame(created, line.getEntryAt(10, 20).getValue());
        assertSame(created, line.getEntryAt(12, 23).getValue());
        assertNull(line.getEntryAt(13, 20));
        assertNull(line.getEntryAt(10, 24));
        assertNull(line.getEntryAt(9, 20));
    }

    @Test
    void delegatesPropertiesAndRestoresMissingNameWithWriteBack() {
        QuestLine line = new QuestLine();
        line.setProperty(NativeProps.NAME, "Custom");
        assertEquals("Custom", line.getUnlocalisedName());
        line.removeProperty(NativeProps.NAME);
        assertFalse(line.hasProperty(NativeProps.NAME));
        assertEquals("New Quest Line", line.getUnlocalisedName());
        assertTrue(line.hasProperty(NativeProps.NAME));
        line.removeAllProps();
        assertEquals("No Description", line.getUnlocalisedDescription());
        assertTrue(line.hasProperty(NativeProps.DESC));
    }

    @Test
    void nbtRoundTripSupportsSkippingAndRejectsSubsets() {
        QuestLine source = new QuestLine();
        source.setProperty(NativeProps.NAME, "Serialized");
        source.put(ID, new QuestLineEntry(4, 5, 6, 7));
        NBTTagCompound serialized = source.writeToNBT(new NBTTagCompound());
        assertTrue(serialized.hasKey("properties"));
        assertTrue(serialized.hasKey("quests"));

        QuestLine restored = new QuestLine();
        restored.readFromNBT(serialized);
        assertEquals("Serialized", restored.getProperty(NativeProps.NAME));
        assertEquals(4, restored.get(ID).getPosX());
        assertFalse(source.writeToNBT(new NBTTagCompound(), true).hasKey("quests"));
        assertThrows(UnsupportedOperationException.class,
            () -> source.writeToNBT(new NBTTagCompound(), List.of(1)));
    }

    @Test
    void readsLegacyIdsSkipsMissingIdsAndHonorsMerge() {
        QuestLine line = new QuestLine();
        UUID retained = UUID.randomUUID();
        line.put(retained, new QuestLineEntry(1, 1));
        NBTTagCompound serialized = new NBTTagCompound();
        NBTTagList quests = new NBTTagList();
        NBTTagCompound legacy = new QuestLineEntry(2, 3).writeToNBT(new NBTTagCompound());
        legacy.setInteger("id", 42);
        quests.appendTag(legacy);
        quests.appendTag(new QuestLineEntry(9, 9).writeToNBT(new NBTTagCompound()));
        serialized.setTag("quests", quests);

        line.readFromNBT(serialized, true);
        assertTrue(line.containsKey(retained));
        assertTrue(line.containsKey(UuidConverter.convertLegacyId(42)));
        assertEquals(2, line.size());
        line.readFromNBT(serialized, false);
        assertFalse(line.containsKey(retained));
        assertEquals(1, line.size());
    }

    @Test
    void wrongQuestListTypeIsTreatedAsEmpty() {
        QuestLine line = new QuestLine();
        line.put(ID, new QuestLineEntry(0, 0));
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setString("quests", "not a list");
        line.readFromNBT(malformed, false);
        assertTrue(line.isEmpty());
    }
}
