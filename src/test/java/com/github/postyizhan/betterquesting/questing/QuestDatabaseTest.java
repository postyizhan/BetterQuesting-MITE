package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.*;

import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagList;
import net.minecraft.NBTTagShort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> wrongLegacyQuestIdTypes() {
        return Stream.of(
            Arguments.of("byte", new NBTTagByte("questID", (byte) 42)),
            Arguments.of("short", new NBTTagShort("questID", (short) 42)),
            Arguments.of("long", new NBTTagLong("questID", 42L)),
            Arguments.of("double", new NBTTagDouble("questID", 42D))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongLegacyQuestIdTypes")
    void wrongNumericLegacyQuestIdTypesAreIgnoredWithoutReaderCrash(String ignored, NBTBase questId) {
        NBTTagCompound definition = new NBTTagCompound();
        definition.setTag("questID", questId);
        NBTTagList definitions = new NBTTagList();
        definitions.appendTag(definition);
        QuestDatabase database = new QuestDatabase();

        assertDoesNotThrow(() -> database.readFromNBT(definitions, false));
        assertTrue(database.isEmpty());

        NBTTagCompound progress = new NBTTagCompound();
        progress.setTag("questID", questId.copy());
        NBTTagList progressEntries = new NBTTagList();
        progressEntries.appendTag(progress);
        assertDoesNotThrow(() -> database.readProgressFromNBT(progressEntries, false));
        assertTrue(database.isEmpty());
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
        Set<UUID> dirtyPlayers = new HashSet<>();
        QuestDatabase database = new QuestDatabase(dirtyPlayers::add);
        UUID removedId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        IQuest removed = database.createNew(removedId);
        IQuest dependent = database.createNew(UUID.randomUUID());
        dependent.getRequirements().add(removedId);
        removed.setComplete(player, 1L);
        dirtyPlayers.clear();
        assertEquals(removedId, database.removeValue(removed));
        assertFalse(dependent.getRequirements().contains(removedId));
        assertEquals(Set.of(player), dirtyPlayers);
    }

    @Test
    void removalApisNotifyDirtySinkAfterReleasingDatabaseMonitor() {
        AtomicReference<QuestDatabase> databaseRef = new AtomicReference<>();
        AtomicBoolean databaseLockHeld = new AtomicBoolean();
        Set<UUID> dirtyPlayers = new HashSet<>();
        QuestDatabase database = new QuestDatabase(player -> {
            databaseLockHeld.set(Thread.holdsLock(databaseRef.get()));
            dirtyPlayers.add(player);
        });
        databaseRef.set(database);
        UUID questId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        database.createNew(questId).setComplete(player, 1L);
        dirtyPlayers.clear();

        database.remove(questId);

        assertFalse(databaseLockHeld.get());
        assertEquals(Set.of(player), dirtyPlayers);

        UUID secondPlayer = UUID.randomUUID();
        IQuest secondQuest = database.createNew(UUID.randomUUID());
        secondQuest.setComplete(secondPlayer, 2L);
        databaseLockHeld.set(false);
        dirtyPlayers.clear();

        database.removeValue(secondQuest);

        assertFalse(databaseLockHeld.get());
        assertEquals(Set.of(secondPlayer), dirtyPlayers);
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

    @Test
    void perPlayerProgressOmitsQuestWithoutCompletionOrTaskData() {
        QuestDatabase database = new QuestDatabase();
        UUID completed = UUID.randomUUID();
        database.createNew(completed).setComplete(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L);
        database.createNew(UUID.randomUUID());

        NBTTagList progress = database.writeProgressToNBT(new NBTTagList(),
            java.util.List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        assertEquals(1, progress.tagCount());
    }
}
