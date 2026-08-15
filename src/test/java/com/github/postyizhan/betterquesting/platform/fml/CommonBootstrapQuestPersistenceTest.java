package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.storage.QuestProgressPersistence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonBootstrapQuestPersistenceTest {
    private static final UUID DELETED_QUEST =
        UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID RETAINED_QUEST =
        UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID ALICE =
        UUID.fromString("00000000-0000-0000-0000-000000000401");

    @TempDir
    Path dataDirectory;

    private final Object owner = new Object();

    @AfterEach
    void discardBoundLifecycles() {
        CommonBootstrap.onQuestWorldSave(owner, true);
    }

    @Test
    void failedProgressStopRetainsDatabaseUntilWorldSaveRetryCompletesBothOnce() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        fixture.quests().createNew(DELETED_QUEST);
        fixture.quests().get(DELETED_QUEST).setComplete(ALICE, 41L);
        storage.failProgressWrites(1);

        CommonBootstrap.onQuestServerStopping(owner, false);

        assertTrue(fixture.progress().isRetryOnWorldSave());
        assertFalse(fixture.quests().isEmpty());
        storage.clearEvents();

        CommonBootstrap.onQuestWorldSave(owner, false);

        assertEquals(List.of(
            QuestProgressPersistence.pathFor(ALICE),
            "QuestDatabase.json"), storage.events());
        assertTrue(fixture.quests().isEmpty());
        assertTrue(fixture.questLines().isEmpty());
        assertFalse(fixture.progress().isRetryOnWorldSave());

        Fixture restarted = loadUnbound(new TrackingStorage(dataDirectory));
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.progressLoadStatus());
        assertTrue(restarted.quests().get(DELETED_QUEST).isComplete(ALICE));
        storage.clearEvents();
        CommonBootstrap.onQuestWorldSave(owner, false);
        assertTrue(storage.events().isEmpty());
    }

    @Test
    void explicitDeletionDiscardsBothLifecyclesWhileProgressStopRetryIsPending() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        fixture.quests().createNew(DELETED_QUEST);
        fixture.quests().get(DELETED_QUEST).setComplete(ALICE, 42L);
        storage.failProgressWrites(1);
        CommonBootstrap.onQuestServerStopping(owner, false);
        storage.clearEvents();

        CommonBootstrap.onQuestWorldSave(owner, true);

        assertTrue(fixture.quests().isEmpty());
        assertTrue(fixture.questLines().isEmpty());
        assertTrue(fixture.progress().dirtyPlayersSnapshot().isEmpty());
        assertTrue(storage.events().isEmpty());
        CommonBootstrap.onQuestWorldSave(owner, true);
        assertTrue(storage.events().isEmpty());
    }

    @Test
    void taskSnapshotRefusalThenSingleInternalSaveRetiresBothLifecyclesWithoutWrites() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        fixture.quests().createNew(DELETED_QUEST);
        fixture.quests().get(DELETED_QUEST).setComplete(ALICE, 43L);
        fixture.quests().get(DELETED_QUEST).getTasks().add(0, new TaskPlaceholder());

        CommonBootstrap.onQuestServerStopping(owner, false);

        assertEquals(QuestProgressLifecycle.State.WRITE_DISABLED, fixture.progress().state());
        assertTrue(fixture.progress().isStopCallbackPending());
        storage.clearEvents();

        CommonBootstrap.onQuestWorldSave(owner, false);

        assertTrue(storage.events().isEmpty());
        assertTrue(fixture.quests().isEmpty());
        assertTrue(fixture.questLines().isEmpty());
        assertTrue(fixture.progress().dirtyPlayersSnapshot().isEmpty());

        CommonBootstrap.onQuestWorldSave(owner, false);
        assertTrue(storage.events().isEmpty());
    }

    @Test
    void explicitDeletionStillCleansAfterTaskSnapshotRefusal() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        fixture.quests().createNew(DELETED_QUEST);
        fixture.quests().get(DELETED_QUEST).setComplete(ALICE, 44L);
        fixture.quests().get(DELETED_QUEST).getTasks().add(0, new TaskPlaceholder());

        CommonBootstrap.onQuestServerStopping(owner, false);
        storage.clearEvents();
        CommonBootstrap.onQuestWorldSave(owner, true);

        assertTrue(storage.events().isEmpty());
        assertTrue(fixture.quests().isEmpty());
        assertTrue(fixture.questLines().isEmpty());
        assertTrue(fixture.progress().dirtyPlayersSnapshot().isEmpty());
    }

    @Test
    void progressFailureSkipsDatabaseCommitAndRestartKeepsCompatibleOldPair() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        seedTwoQuests(fixture);
        CommonBootstrap.onQuestWorldSave(owner, false);
        byte[] originalDatabase = Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json"));

        fixture.quests().remove(DELETED_QUEST);
        storage.failProgressWrites(1);
        storage.clearEvents();
        CommonBootstrap.onQuestWorldSave(owner, false);

        assertEquals(List.of(QuestProgressPersistence.pathFor(ALICE)), storage.events());
        assertArrayEquals(originalDatabase, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));

        Fixture restarted = loadUnbound(new TrackingStorage(dataDirectory));
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.progressLoadStatus());
        assertTrue(restarted.quests().get(DELETED_QUEST).isComplete(ALICE));
        assertTrue(restarted.quests().get(RETAINED_QUEST).isComplete(ALICE));
    }

    @Test
    void successfulDefinitionChangeCommitsProgressBeforeDatabaseAndRestartsCleanly() throws IOException {
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        seedTwoQuests(fixture);
        CommonBootstrap.onQuestWorldSave(owner, false);

        fixture.quests().remove(DELETED_QUEST);
        storage.clearEvents();
        CommonBootstrap.onQuestWorldSave(owner, false);

        assertEquals(List.of(
            QuestProgressPersistence.pathFor(ALICE),
            "QuestDatabase.json"), storage.events());
        Fixture restarted = loadUnbound(new TrackingStorage(dataDirectory));
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, restarted.progressLoadStatus());
        assertFalse(restarted.quests().containsKey(DELETED_QUEST));
        assertTrue(restarted.quests().get(RETAINED_QUEST).isComplete(ALICE));
    }

    @Test
    void futureProgressFormatBlocksProgressAndDatabaseWritesThroughStop() throws IOException {
        byte[] original = "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path progressPath = dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE));
        Files.createDirectories(progressPath.getParent());
        Files.write(progressPath, original);
        TrackingStorage storage = new TrackingStorage(dataDirectory);
        Fixture fixture = start(storage);
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, fixture.progressLoadStatus());
        fixture.quests().createNew(RETAINED_QUEST);

        CommonBootstrap.onQuestWorldSave(owner, false);
        CommonBootstrap.onQuestServerStopping(owner, false);

        assertTrue(storage.events().isEmpty());
        assertFalse(Files.exists(dataDirectory.resolve("QuestDatabase.json")));
        assertArrayEquals(original, Files.readAllBytes(progressPath));
        assertTrue(fixture.quests().isEmpty());
        assertTrue(fixture.questLines().isEmpty());
    }

    @Test
    void failedQuestDatabaseSessionIgnoresMismatchedCleanupAndRebindsCleanly() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestDatabase.json"),
            "{\"questDatabase:9\":{},\"questLines:9\":{},\"mitePortFormat:8\":\"2\"}");
        QuestDatabase firstQuests = new QuestDatabase();
        QuestLineDatabase firstLines = new QuestLineDatabase();
        QuestDatabaseLifecycle failedDatabase = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory), firstQuests, firstLines, "test");
        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
            failedDatabase.onServerStarted());
        CommonBootstrap.bindQuestLifecycles(owner, failedDatabase, null);
        firstQuests.createNew(DELETED_QUEST);

        Object otherOwner = new Object();
        CommonBootstrap.onQuestWorldSave(otherOwner, true);
        CommonBootstrap.onQuestWorldSave(owner, false);
        assertTrue(firstQuests.isEmpty());

        QuestDatabase secondQuests = new QuestDatabase();
        QuestLineDatabase secondLines = new QuestLineDatabase();
        QuestDatabaseLifecycle secondDatabase = new QuestDatabaseLifecycle(
            new DirectoryWorldStorage(dataDirectory.resolveSibling(dataDirectory.getFileName() + "-second")),
            secondQuests, secondLines, "test");
        secondDatabase.onServerStarted();
        CommonBootstrap.bindQuestLifecycles(otherOwner, secondDatabase, null);
        secondQuests.createNew(RETAINED_QUEST);
        CommonBootstrap.onQuestWorldSave(owner, true);
        assertTrue(secondQuests.containsKey(RETAINED_QUEST));
        CommonBootstrap.onQuestWorldSave(otherOwner, true);
        assertTrue(secondQuests.isEmpty());
    }

    private Fixture start(TrackingStorage storage) throws IOException {
        Fixture fixture = loadUnbound(storage);
        CommonBootstrap.bindQuestLifecycles(owner, fixture.database(), fixture.progress());
        return fixture;
    }

    private static Fixture loadUnbound(TrackingStorage storage) throws IOException {
        QuestDatabase quests = new QuestDatabase();
        QuestLineDatabase questLines = new QuestLineDatabase();
        QuestDatabaseLifecycle database = new QuestDatabaseLifecycle(
            storage, quests, questLines, "test");
        database.onServerStarted();
        QuestProgressLifecycle progress = new QuestProgressLifecycle(storage, quests);
        QuestProgressPersistence.LoadStatus progressLoadStatus = progress.onServerStarted().status();
        return new Fixture(quests, questLines, database, progress, progressLoadStatus);
    }

    private static void seedTwoQuests(Fixture fixture) {
        fixture.quests().createNew(DELETED_QUEST);
        fixture.quests().createNew(RETAINED_QUEST);
        fixture.quests().get(DELETED_QUEST).setComplete(ALICE, 51L);
        fixture.quests().get(RETAINED_QUEST).setComplete(ALICE, 52L);
    }

    private record Fixture(QuestDatabase quests, QuestLineDatabase questLines,
                           QuestDatabaseLifecycle database, QuestProgressLifecycle progress,
                           QuestProgressPersistence.LoadStatus progressLoadStatus) {
    }

    private static final class TrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private final List<String> events = new ArrayList<>();
        private int progressFailures;

        private TrackingStorage(Path directory) {
            delegate = new DirectoryWorldStorage(directory);
        }

        void failProgressWrites(int count) {
            progressFailures = count;
        }

        List<String> events() {
            return List.copyOf(events);
        }

        void clearEvents() {
            events.clear();
        }

        @Override public boolean isAvailable() { return delegate.isAvailable(); }
        @Override public Optional<Path> getDataDirectory() { return delegate.getDataDirectory(); }
        @Override public Optional<String> getDisabledReason() { return delegate.getDisabledReason(); }
        @Override public boolean exists(String path) throws IOException { return delegate.exists(path); }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException {
            return delegate.read(path, reader);
        }
        @Override public List<String> readLines(String path) throws IOException { return delegate.readLines(path); }
        @Override public List<String> list(String path, String suffix) throws IOException {
            return delegate.list(path, suffix);
        }
        @Override public boolean delete(String path) throws IOException { return delegate.delete(path); }
        @Override public void appendLine(String path, String line) throws IOException {
            delegate.appendLine(path, line);
        }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            delegate.writeAtomically(path, writer);
        }
        @Override public void writeAtomically(String path, OutputWriter writer,
            ReadbackValidator validator) throws IOException {
            events.add(path);
            if (path.startsWith(QuestProgressPersistence.DIRECTORY + "/") && progressFailures-- > 0) {
                throw new IOException("injected progress write failure");
            }
            delegate.writeAtomically(path, writer, validator);
        }
        @Override public Optional<Path> backup(String path) throws IOException { return delegate.backup(path); }
        @Override public void flush() throws IOException { delegate.flush(); }
    }
}
