package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.*;

import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class QuestDatabaseTest {
    @Test
    void uuidRoundTrips() {
        QuestDatabase source = new QuestDatabase();
        UUID id = UUID.randomUUID();
        source.createNew(id);
        QuestDatabase loaded = new QuestDatabase();
        loaded.readFromNBT(source.writeToNBT(new NBTTagList(), null), false);
        assertNotNull(loaded.get(id));
    }

    @Test
    void readsLegacyQuestId() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("questID", 42);
        tag.setTag("properties", new NBTTagCompound());
        tag.setTag("tasks", new NBTTagList());
        tag.setTag("rewards", new NBTTagList());
        NBTTagList list = new NBTTagList();
        list.appendTag(tag);
        QuestDatabase database = new QuestDatabase();
        database.readFromNBT(list, false);
        assertNotNull(database.get(UuidConverter.convertLegacyId(42)));
    }

    @Test
    void removalCleansOtherRequirements() {
        QuestDatabase database = new QuestDatabase();
        UUID removedId = UUID.randomUUID();
        UUID dependentId = UUID.randomUUID();
        IQuest removed = database.createNew(removedId);
        IQuest dependent = database.createNew(dependentId);
        dependent.getRequirements().add(removedId);
        assertSame(removed, database.remove(removedId));
        assertFalse(dependent.getRequirements().contains(removedId));
    }

    @Test
    void removeValueAlsoCleansOtherRequirements() {
        QuestDatabase database = new QuestDatabase();
        UUID removedId = UUID.randomUUID();
        IQuest removed = database.createNew(removedId);
        IQuest dependent = database.createNew(UUID.randomUUID());
        dependent.getRequirements().add(removedId);
        assertEquals(removedId, database.removeValue(removed));
        assertFalse(dependent.getRequirements().contains(removedId));
    }

    @Test
    void nullQuestIsSkippedByConfigAndProgressPersistence() {
        QuestDatabase database = new QuestDatabase();
        UUID nullId = UUID.randomUUID();
        database.put(nullId, null);
        assertDoesNotThrow(() -> database.writeToNBT(new NBTTagList(), null));
        assertDoesNotThrow(() -> database.writeProgressToNBT(new NBTTagList(), null));
        assertEquals(0, database.writeToNBT(new NBTTagList(), null).tagCount());
        assertEquals(0, database.writeProgressToNBT(new NBTTagList(), null).tagCount());

        NBTTagCompound progress = com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType.QUEST.writeId(nullId);
        NBTTagList progressList = new NBTTagList();
        progressList.appendTag(progress);
        assertDoesNotThrow(() -> database.readProgressFromNBT(progressList, false));
    }
}
