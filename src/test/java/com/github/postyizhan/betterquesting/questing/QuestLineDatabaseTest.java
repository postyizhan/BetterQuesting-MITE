package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.questing.IQuestLine;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.AbstractMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class QuestLineDatabaseTest {
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void createsLinesAndLazilyAssignsOrder() {
        QuestLineDatabase database = new QuestLineDatabase();
        assertEquals(-1, database.getOrderIndex(FIRST));
        IQuestLine line = database.createNew(FIRST);
        assertNotNull(line);
        assertEquals(0, database.getOrderIndex(FIRST));
        database.createNew(SECOND);
        assertEquals(1, database.getOrderIndex(SECOND));
    }

    @Test
    void clampsOrderAndReturnsDisplaySortedEntries() {
        QuestLineDatabase database = threeLines();
        database.getOrderIndex(FIRST);
        database.getOrderIndex(SECOND);
        database.getOrderIndex(THIRD);
        database.setOrderIndex(THIRD, -99);
        assertEquals(List.of(THIRD, FIRST, SECOND), keys(database));
        database.setOrderIndex(THIRD, 99);
        assertEquals(List.of(FIRST, SECOND, THIRD), keys(database));
    }

    @Test
    void replacesOrderedEntriesAndClearRemovesCachedOrder() {
        QuestLineDatabase database = new QuestLineDatabase();
        QuestLine first = new QuestLine();
        QuestLine second = new QuestLine();
        database.setOrderedEntries(List.of(
            new AbstractMap.SimpleEntry<>(SECOND, second),
            new AbstractMap.SimpleEntry<>(FIRST, first)));
        assertEquals(List.of(SECOND, FIRST), keys(database));
        database.clear();
        assertTrue(database.isEmpty());
        assertEquals(-1, database.getOrderIndex(FIRST));
        database.put(FIRST, first);
        assertEquals(0, database.getOrderIndex(FIRST));
    }

    @Test
    void writesSubsetAndSkipsNullLines() {
        QuestLineDatabase database = threeLines();
        database.put(THIRD, null);
        NBTTagList serialized = database.writeToNBT(new NBTTagList(), List.of(SECOND, THIRD));
        assertEquals(1, serialized.tagCount());
        NBTTagCompound only = (NBTTagCompound) serialized.tagAt(0);
        assertEquals(SECOND, UuidValueType.QUEST_LINE.readId(only));
        assertTrue(only.hasKey("order"));
    }

    @Test
    void rebuildsOrderAndSupportsLegacyLineIds() {
        QuestLineDatabase database = new QuestLineDatabase();
        NBTTagList serialized = new NBTTagList();
        serialized.appendTag(lineTag(SECOND, 1));
        serialized.appendTag(lineTag(FIRST, 0));
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("lineID", 7);
        legacy.setInteger("order", 2);
        serialized.appendTag(legacy);

        database.readFromNBT(serialized, false);
        UUID legacyId = UuidConverter.convertLegacyId(7);
        assertTrue(database.containsKey(legacyId));
        assertEquals(List.of(FIRST, SECOND, legacyId), keys(database));
    }

    @Test
    void mergeReusesExistingLineAndUnassignedLegacyLinesGetGeneratedIds() {
        QuestLineDatabase database = new QuestLineDatabase();
        IQuestLine existing = database.createNew(FIRST);
        existing.put(THIRD, new QuestLineEntry(1, 1));
        NBTTagList serialized = new NBTTagList();
        NBTTagCompound assigned = lineTag(FIRST, 0);
        NBTTagList quests = new NBTTagList();
        NBTTagCompound newQuest = new QuestLineEntry(2, 2).writeToNBT(new NBTTagCompound());
        UuidValueType.QUEST.writeId(SECOND, newQuest);
        quests.appendTag(newQuest);
        assigned.setTag("quests", quests);
        serialized.appendTag(assigned);
        serialized.appendTag(new NBTTagCompound());

        database.readFromNBT(serialized, true);
        assertEquals(2, database.size());
        assertTrue(database.get(FIRST).containsKey(THIRD));
        assertTrue(database.get(FIRST).containsKey(SECOND));
    }

    @Test
    void unassignedOrderedLineNeverPlacesNullInDisplayOrder() {
        QuestLineDatabase database = new QuestLineDatabase();
        NBTTagCompound unassigned = new NBTTagCompound();
        unassigned.setInteger("order", 0);
        NBTTagList serialized = new NBTTagList();
        serialized.appendTag(unassigned);
        database.readFromNBT(serialized, false);

        assertEquals(1, database.size());
        assertFalse(database.getOrderedEntries().stream().anyMatch(entry -> entry.getKey() == null));
        assertNull(database.get(null));
    }

    private static QuestLineDatabase threeLines() {
        QuestLineDatabase database = new QuestLineDatabase();
        database.createNew(FIRST);
        database.createNew(SECOND);
        database.createNew(THIRD);
        return database;
    }

    private static NBTTagCompound lineTag(UUID id, int order) {
        NBTTagCompound tag = new NBTTagCompound();
        UuidValueType.QUEST_LINE.writeId(id, tag);
        tag.setInteger("order", order);
        return tag;
    }

    private static List<UUID> keys(QuestLineDatabase database) {
        return database.getOrderedEntries().stream().map(java.util.Map.Entry::getKey).toList();
    }
}
