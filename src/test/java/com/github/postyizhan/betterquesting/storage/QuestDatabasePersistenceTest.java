package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestDatabasePersistenceTest {
    private static final UUID QUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FIRST_LINE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SECOND_LINE = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void clearSingletons() {
        QuestDatabase.INSTANCE.clear();
        QuestLineDatabase.INSTANCE.clear();
    }

    @Test
    void sharedDocumentRoundTripsQuestAndLineOrder() throws IOException {
        QuestDatabase.INSTANCE.createNew(QUEST_ID).setProperty(NativeProps.NAME, "Persisted Quest");
        QuestLineDatabase.INSTANCE.createNew(FIRST_LINE).setProperty(NativeProps.NAME, "First");
        QuestLineDatabase.INSTANCE.createNew(SECOND_LINE).setProperty(NativeProps.NAME, "Second");
        QuestLineDatabase.INSTANCE.setOrderIndex(SECOND_LINE, 0);
        QuestLineDatabase.INSTANCE.setOrderIndex(FIRST_LINE, 1);

        QuestDatabasePersistence persistence = persistence();
        persistence.save("1.0.0");
        QuestDatabase.INSTANCE.clear();
        QuestLineDatabase.INSTANCE.clear();

        assertEquals(JsonDocumentStore.Outcome.LOADED, persistence.load());
        assertEquals("Persisted Quest", QuestDatabase.INSTANCE.get(QUEST_ID).getProperty(NativeProps.NAME));
        assertEquals(List.of(SECOND_LINE, FIRST_LINE),
            QuestLineDatabase.INSTANCE.getOrderedEntries().stream().map(java.util.Map.Entry::getKey).toList());
    }

    @Test
    void absentAndMalformedDocumentsClearBothDatabases() throws IOException {
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(FIRST_LINE);
        QuestDatabasePersistence persistence = persistence();
        assertEquals(JsonDocumentStore.Outcome.ABSENT, persistence.load());
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());

        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(FIRST_LINE);
        Files.writeString(dataDirectory.resolve(QuestDatabasePersistence.PATH), "{\"broken\":", StandardCharsets.UTF_8);
        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, persistence.load());
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    @Test
    void unsupportedSchemaPreservesExactBytesAndDisablesWrites() throws IOException {
        String original = "{\"questDatabase:9\":{},\"questLines:9\":{},\"mitePortFormat:8\":\"2\"}";
        Files.write(dataDirectory.resolve(QuestDatabasePersistence.PATH), original.getBytes(StandardCharsets.UTF_8));
        QuestDatabasePersistence persistence = persistence();
        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, persistence.load());
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        persistence.save("1.0.0");
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(dataDirectory.resolve(QuestDatabasePersistence.PATH)));
        assertTrue(persistence.isWritesDisabled());
        assertTrue(Files.exists(dataDirectory.resolve("malformed_QuestDatabase.json.json")));
    }

    @Test
    void atomicSaveContainsBothRootsAndSchemaStamps() throws IOException {
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(FIRST_LINE);
        persistence().save("build-1");
        String document = Files.readString(dataDirectory.resolve(QuestDatabasePersistence.PATH));
        NBTTagCompound root = new NbtJsonCodec().toNbt(JsonDocuments.parseObject(document), new NBTTagCompound(), true);
        assertTrue(root.hasKey("questDatabase") && root.hasKey("questLines"));
        assertEquals("3.1.0", root.getString("format"));
        assertEquals("build-1", root.getString("build"));
        assertEquals("1", root.getString("mitePortFormat"));
        assertFalse(Files.exists(dataDirectory.resolve("QuestDatabase.json.tmp")));
    }

    private QuestDatabasePersistence persistence() {
        return new QuestDatabasePersistence(
            QuestDatabase.INSTANCE, QuestLineDatabase.INSTANCE,
            new JsonDocumentStore(new DirectoryWorldStorage(dataDirectory)));
    }
}
