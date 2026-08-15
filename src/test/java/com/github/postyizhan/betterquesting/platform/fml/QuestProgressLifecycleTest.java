package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.storage.QuestProgressPersistence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestProgressLifecycleTest {
    private static final UUID QUEST = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000402");

    @TempDir
    Path dataDirectory;

    @Test
    void dirtyQuestMutationWritesOnlyThatPlayer() throws IOException {
        QuestDatabase quests = database();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT, lifecycle.onServerStarted().status());

        quests.get(QUEST).setComplete(ALICE, 1L);
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());
        lifecycle.onWorldSave();

        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
        assertFalse(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB))));
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        assertEquals(0, storage.flushes);
    }

    @Test
    void failedStopRetainsDirtyPlayerAndRetriesOnceOnWorldSave() throws IOException {
        QuestDatabase quests = database();
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 1);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 2L);

        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertTrue(lifecycle.isRetryOnWorldSave());
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());

        lifecycle.onWorldSave();
        assertFalse(lifecycle.isRetryOnWorldSave());
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
    }

    @Test
    void repeatedStopFailuresRetainEveryUnsavedPlayerSnapshotUntilRetrySucceeds() throws IOException {
        QuestDatabase quests = database();
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 3);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 10L);
        quests.get(QUEST).setComplete(BOB, 20L);

        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertEquals(Set.of(ALICE, BOB), lifecycle.dirtyPlayersSnapshot());
        assertThrows(IOException.class, lifecycle::onWorldSave);
        assertTrue(lifecycle.isRetryOnWorldSave());
        assertEquals(Set.of(ALICE, BOB), lifecycle.dirtyPlayersSnapshot());
        assertThrows(IOException.class, lifecycle::onWorldSave);
        assertTrue(lifecycle.isRetryOnWorldSave());
        assertEquals(Set.of(ALICE, BOB), lifecycle.dirtyPlayersSnapshot());

        lifecycle.onWorldSave();
        assertFalse(lifecycle.isRetryOnWorldSave());
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB))));
    }

    @Test
    void taskBearingStopSnapshotRefusalDisablesWritesAndRetainsStateThroughCallback() throws IOException {
        QuestDatabase quests = database();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 21L);
        quests.get(QUEST).getTasks().add(0, new TaskPlaceholder());

        assertThrows(IllegalStateException.class, lifecycle::onServerStopping);

        assertEquals(QuestProgressLifecycle.State.WRITE_DISABLED, lifecycle.state());
        assertTrue(lifecycle.isStopCallbackPending());
        assertTrue(lifecycle.isLiveStatePreserved());
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertEquals(0, storage.writes);
        assertEquals(0, storage.flushes);

        quests.get(QUEST).setComplete(BOB, 22L);
        lifecycle.onWorldSave();

        assertFalse(lifecycle.isStopCallbackPending());
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        assertTrue(quests.get(QUEST).isComplete(BOB));
        assertEquals(0, storage.writes);
        assertFalse(Files.exists(dataDirectory.resolve("QuestProgress")));
    }

    @Test
    void partialStopRetryWritesOnlyBobAfterAliceSucceedsAndPreservesBobSnapshot() throws IOException {
        QuestDatabase quests = database();
        PlayerFailingStorage storage = new PlayerFailingStorage(dataDirectory, BOB, 2);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 10L);
        quests.get(QUEST).setComplete(BOB, 20L);

        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertEquals(1, storage.attempts(ALICE));
        assertEquals(1, storage.attempts(BOB));
        assertEquals(Set.of(BOB), lifecycle.dirtyPlayersSnapshot());

        quests.get(QUEST).setComplete(BOB, 99L);
        assertThrows(IOException.class, lifecycle::onWorldSave);
        assertEquals(1, storage.attempts(ALICE));
        assertEquals(2, storage.attempts(BOB));
        assertEquals(Set.of(BOB), lifecycle.dirtyPlayersSnapshot());

        lifecycle.onWorldSave();
        assertEquals(1, storage.attempts(ALICE));
        assertEquals(3, storage.attempts(BOB));

        QuestDatabase restarted = database();
        QuestProgressPersistence.LoadReport report = new QuestProgressPersistence(
            restarted, new DirectoryWorldStorage(dataDirectory)).load();
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertEquals(10L, restarted.get(QUEST).getCompletionInfo(ALICE).getLong("timestamp"));
        assertEquals(20L, restarted.get(QUEST).getCompletionInfo(BOB).getLong("timestamp"));
    }

    @Test
    void quarantinedStartupDetachesSinkAndNeverWritesLater() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Path malformed = dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE));
        Files.writeString(malformed, "{\"broken\":");
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);

        assertEquals(QuestProgressPersistence.LoadStatus.QUARANTINED, lifecycle.onServerStarted().status());
        quests.get(QUEST).setComplete(ALICE, 30L);
        lifecycle.onWorldSave();
        lifecycle.onServerStopping();

        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        assertEquals("{\"broken\":", Files.readString(malformed));
        assertFalse(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB))));
    }

    @Test
    void failedStartupRebindsSinkOnRetryAndRepeatedSuccessStaysIdempotent() throws IOException {
        QuestDatabase quests = database();
        FailingLoadStorage storage = new FailingLoadStorage(dataDirectory);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);

        assertThrows(IOException.class, lifecycle::onServerStarted);
        assertEquals(QuestProgressLifecycle.State.WRITE_DISABLED, lifecycle.state());

        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT, lifecycle.onServerStarted().status());
        assertEquals(QuestProgressLifecycle.State.WRITABLE, lifecycle.state());
        quests.get(QUEST).setComplete(ALICE, 30L);
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());
        lifecycle.onWorldSave();
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, lifecycle.onServerStarted().status());
        quests.get(QUEST).setComplete(BOB, 31L);
        assertEquals(Set.of(BOB), lifecycle.dirtyPlayersSnapshot());
        lifecycle.onWorldSave();
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB))));
    }

    @Test
    void blockedStartupReportRebindsSinkWhenRetrySucceeds() throws IOException {
        Path legacy = dataDirectory.resolve(QuestProgressPersistence.LEGACY_PATH);
        Files.writeString(legacy, "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}");
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED,
            lifecycle.onServerStarted().status());
        assertEquals(QuestProgressLifecycle.State.WRITE_DISABLED, lifecycle.state());
        quests.get(QUEST).setComplete(ALICE, 29L);
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());

        Files.delete(legacy);
        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT,
            lifecycle.onServerStarted().status());
        assertEquals(QuestProgressLifecycle.State.WRITABLE, lifecycle.state());
        quests.get(QUEST).setComplete(ALICE, 30L);
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());

        lifecycle.onWorldSave();
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
    }

    @Test
    void emptyLegacyStartupMigratesAndKeepsWritesEnabled() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestProgress.json"), "{\"questProgress:9\":{}}");
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);

        QuestProgressPersistence.LoadReport report = lifecycle.onServerStarted();
        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT, report.status());
        assertEquals(QuestProgressPersistence.MigrationStatus.MIGRATED,
            report.legacyMigration().orElseThrow().status());
        quests.get(QUEST).setComplete(ALICE, 31L);
        lifecycle.onWorldSave();

        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
        assertEquals("{\"questProgress:9\":{}}",
            Files.readString(dataDirectory.resolve("QuestProgress.json")));
    }

    @Test
    void completionOnlyLegacyStartupMigratesIntoWritableLifecycle() throws IOException {
        String original = "{\"questProgress:9\":{\"0:10\":{"
            + "\"questIDHigh:4\":0,\"questIDLow:4\":769,"
            + "\"completed:9\":{\"0:10\":{\"uuid:8\":\"" + ALICE
            + "\",\"timestamp:4\":31}},\"tasks:9\":{}}}}";
        Files.writeString(dataDirectory.resolve("QuestProgress.json"), original);
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);

        QuestProgressPersistence.LoadReport report = lifecycle.onServerStarted();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status());
        assertEquals(QuestProgressLifecycle.State.WRITABLE, lifecycle.state());
        assertTrue(quests.get(QUEST).isComplete(ALICE));
        quests.get(QUEST).setComplete(BOB, 32L);
        assertEquals(Set.of(BOB), lifecycle.dirtyPlayersSnapshot());
        lifecycle.onWorldSave();
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE))));
        assertTrue(Files.exists(dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB))));
        assertEquals(original, Files.readString(dataDirectory.resolve("QuestProgress.json")));
    }

    @Test
    void futureCanonicalAndLegacyFormatsDisableAllLaterProgressWrites() throws IOException {
        byte[] original = "{\"mitePortFormat:8\":\"2\",\"questProgress:9\":{}}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (boolean legacy : List.of(false, true)) {
            Path world = dataDirectory.resolve(legacy ? "legacy" : "canonical");
            Path target = legacy
                ? world.resolve("QuestProgress.json")
                : world.resolve(QuestProgressPersistence.pathFor(ALICE));
            Files.createDirectories(target.getParent());
            Files.write(target, original);
            QuestDatabase quests = database();
            QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
                new DirectoryWorldStorage(world), quests);

            assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED,
                lifecycle.onServerStarted().status());
            assertEquals(QuestProgressLifecycle.State.WRITE_DISABLED, lifecycle.state());
            quests.get(QUEST).setComplete(ALICE, 32L);
            lifecycle.onWorldSave();
            lifecycle.onServerStopping();

            assertArrayEquals(original, Files.readAllBytes(target));
            try (java.util.stream.Stream<Path> paths = Files.walk(world)) {
                assertEquals(List.of(world.relativize(target).toString().replace('\\', '/')),
                    paths.filter(Files::isRegularFile)
                        .map(world::relativize)
                        .map(path -> path.toString().replace('\\', '/'))
                        .sorted()
                        .toList());
            }
        }
    }

    @Test
    void blockedTaskFilesStayByteExactAcrossRestartsAndLaterSaves() throws IOException {
        Files.createDirectories(dataDirectory.resolve("QuestProgress"));
        Path alicePath = dataDirectory.resolve(QuestProgressPersistence.pathFor(ALICE));
        Path bobPath = dataDirectory.resolve(QuestProgressPersistence.pathFor(BOB));
        byte[] alice = taskDocument(ALICE, true, "alice-placeholder").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bob = taskDocument(BOB, false, "bob-placeholder").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(alicePath, alice);
        Files.write(bobPath, bob);
        List<String> initialFiles = fileTree();
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);

        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, lifecycle.onServerStarted().status());
        assertEquals(QuestProgressPersistence.LoadStatus.BLOCKED, lifecycle.onServerStarted().status());
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));

        quests.get(QUEST).setComplete(ALICE, 40L);
        quests.get(QUEST).setComplete(BOB, 41L);
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
        lifecycle.onWorldSave();
        lifecycle.onServerStopping();

        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertFalse(quests.get(QUEST).isComplete(BOB));
        assertArrayEquals(alice, Files.readAllBytes(alicePath));
        assertArrayEquals(bob, Files.readAllBytes(bobPath));
        assertEquals(initialFiles, fileTree());
    }

    @Test
    void normalStopSavesFlushesAndClearsWorldProgress() throws IOException {
        QuestDatabase quests = database();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(storage, quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 3L);

        lifecycle.onServerStopping();

        assertEquals(1, storage.flushes);
        assertFalse(quests.get(QUEST).isComplete(ALICE));
        assertTrue(lifecycle.dirtyPlayersSnapshot().isEmpty());
    }

    @Test
    void deletingWorldNeverRecreatesProgressDirectory() throws IOException {
        QuestDatabase quests = database();
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 3L);
        Files.delete(dataDirectory);

        lifecycle.onWorldSave(true);
        lifecycle.onServerStopping(true);

        assertFalse(Files.exists(dataDirectory));
        assertFalse(quests.get(QUEST).isComplete(ALICE));
    }

    @Test
    void restartLoadsSavedPlayerAndAbsentSecondWorldClearsIt() throws IOException {
        QuestDatabase first = database();
        QuestProgressLifecycle firstLifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), first);
        firstLifecycle.onServerStarted();
        first.get(QUEST).setComplete(ALICE, 4L);
        firstLifecycle.onWorldSave();

        QuestDatabase restarted = database();
        QuestProgressLifecycle restartedLifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), restarted);
        assertEquals(QuestProgressPersistence.LoadStatus.LOADED,
            restartedLifecycle.onServerStarted().status());
        assertTrue(restarted.get(QUEST).isComplete(ALICE));

        Path secondWorld = dataDirectory.resolveSibling(dataDirectory.getFileName() + "-second");
        QuestProgressLifecycle secondLifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(secondWorld), restarted);
        assertEquals(QuestProgressPersistence.LoadStatus.ABSENT,
            secondLifecycle.onServerStarted().status());
        assertFalse(restarted.get(QUEST).isComplete(ALICE));
    }

    @Test
    void deletingCompletedQuestRewritesPlayerFileWithoutStaleId() throws IOException {
        UUID retainedQuest = UUID.fromString("00000000-0000-0000-0000-000000000302");
        QuestDatabase quests = database();
        quests.createNew(retainedQuest);
        QuestProgressLifecycle lifecycle = new QuestProgressLifecycle(
            new DirectoryWorldStorage(dataDirectory), quests);
        lifecycle.onServerStarted();
        quests.get(QUEST).setComplete(ALICE, 31L);
        quests.get(retainedQuest).setComplete(ALICE, 32L);
        lifecycle.onWorldSave();

        quests.remove(QUEST);
        assertEquals(Set.of(ALICE), lifecycle.dirtyPlayersSnapshot());
        lifecycle.onWorldSave();

        QuestDatabase restarted = new QuestDatabase();
        restarted.createNew(retainedQuest);
        QuestProgressPersistence.LoadReport report = new QuestProgressPersistence(
            restarted, new DirectoryWorldStorage(dataDirectory)).load();

        assertEquals(QuestProgressPersistence.LoadStatus.LOADED, report.status(), report.issues().toString());
        assertTrue(restarted.get(retainedQuest).isComplete(ALICE));
    }

    private static QuestDatabase database() {
        QuestDatabase quests = new QuestDatabase();
        quests.createNew(QUEST);
        return quests;
    }

    private static String taskDocument(UUID player, boolean completeUsersOnly, String marker) {
        String task = completeUsersOnly
            ? "\"completeUsers:9\":{\"0:8\":\"" + player + "\"}"
            : "\"taskID:8\":\"betterquesting:placeholder\",\"opaque:8\":\"" + marker + "\"";
        return "{\"questProgress:9\":{\"0:10\":{\"questIDHigh:4\":"
            + QUEST.getMostSignificantBits() + ",\"questIDLow:4\":" + QUEST.getLeastSignificantBits()
            + ",\"completed:9\":{\"0:10\":{\"uuid:8\":\"" + player
            + "\"}},\"tasks:9\":{\"0:10\":{" + task + "}}}}}";
    }

    private List<String> fileTree() throws IOException {
        if (!Files.exists(dataDirectory)) return List.of();
        try (java.util.stream.Stream<Path> paths = Files.walk(dataDirectory)) {
            return paths.filter(Files::isRegularFile)
                .map(dataDirectory::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    private static class FlushTrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int flushes;
        private int writes;

        private FlushTrackingStorage(Path directory) {
            delegate = new DirectoryWorldStorage(directory);
        }

        @Override public boolean isAvailable() { return delegate.isAvailable(); }
        @Override public Optional<Path> getDataDirectory() { return delegate.getDataDirectory(); }
        @Override public Optional<String> getDisabledReason() { return delegate.getDisabledReason(); }
        @Override public boolean exists(String path) throws IOException { return delegate.exists(path); }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException { return delegate.read(path, reader); }
        @Override public List<String> readLines(String path) throws IOException { return delegate.readLines(path); }
        @Override public List<String> list(String path, String suffix) throws IOException { return delegate.list(path, suffix); }
        @Override public boolean delete(String path) throws IOException { return delegate.delete(path); }
        @Override public void appendLine(String path, String line) throws IOException { delegate.appendLine(path, line); }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException { delegate.writeAtomically(path, writer); }
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator) throws IOException { writes++; delegate.writeAtomically(path, writer, validator); }
        @Override public Optional<Path> backup(String path) throws IOException { return delegate.backup(path); }
        @Override public void flush() throws IOException { flushes++; delegate.flush(); }
    }

    private static final class FailingWriteStorage extends FlushTrackingStorage {
        private int writesToFail;

        private FailingWriteStorage(Path directory, int writesToFail) {
            super(directory);
            this.writesToFail = writesToFail;
        }

        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator)
            throws IOException {
            if (writesToFail-- > 0) throw new IOException("injected write failure");
            super.writeAtomically(path, writer, validator);
        }
    }

    private static final class FailingLoadStorage extends FlushTrackingStorage {
        private boolean fail = true;

        private FailingLoadStorage(Path directory) {
            super(directory);
        }

        @Override public List<String> list(String path, String suffix) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("injected load failure");
            }
            return super.list(path, suffix);
        }
    }

    private static final class PlayerFailingStorage extends FlushTrackingStorage {
        private final String failingPath;
        private final Map<String, Integer> attempts = new java.util.HashMap<>();
        private int failuresRemaining;

        private PlayerFailingStorage(Path directory, UUID failingPlayer, int failuresRemaining) {
            super(directory);
            this.failingPath = QuestProgressPersistence.pathFor(failingPlayer);
            this.failuresRemaining = failuresRemaining;
        }

        private int attempts(UUID player) {
            return attempts.getOrDefault(QuestProgressPersistence.pathFor(player), 0);
        }

        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator)
            throws IOException {
            attempts.merge(path, 1, Integer::sum);
            if (failingPath.equals(path) && failuresRemaining-- > 0) {
                throw new IOException("injected Bob write failure");
            }
            super.writeAtomically(path, writer, validator);
        }
    }
}
