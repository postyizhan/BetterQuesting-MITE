package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.google.gson.JsonElement;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestDatabaseLifecycleTest {
    private static final UUID QUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void clearSingletons() {
        QuestDatabase.INSTANCE.clear();
        QuestLineDatabase.INSTANCE.clear();
    }

    @Test
    void deletingWorldSaveBeforeStopNeverRecreatesDirectory() throws IOException {
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestDatabaseLifecycle lifecycle = lifecycle(storage);
        Files.delete(dataDirectory);
        lifecycle.onWorldSave(true);
        assertFalse(Files.exists(dataDirectory));
        lifecycle.onServerStopping(true);
        assertFalse(Files.exists(dataDirectory));
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    @Test
    void unsupportedAndWronglyTypedSchemaPreserveBytesAcrossWorldSaveAndStop() throws IOException {
        for (String original : List.of(
            "{\"questDatabase:9\":{},\"questLines:9\":{},\"mitePortFormat:8\":\"2\"}",
            "{\"questDatabase:9\":{},\"questLines:9\":{},\"mitePortFormat:3\":2}"
        )) {
            Files.write(dataDirectory.resolve("QuestDatabase.json"), original.getBytes(StandardCharsets.UTF_8));
            QuestDatabaseLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));
            assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
                lifecycle.onServerStarted());
            QuestDatabase.INSTANCE.createNew(QUEST_ID);
            QuestLineDatabase.INSTANCE.createNew(LINE_ID);
            lifecycle.onWorldSave();
            assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
            lifecycle.onServerStopping();
            assertArrayEquals(original.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
            assertTrue(QuestDatabase.INSTANCE.isEmpty());
            assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
        }
    }

    @Test
    void malformedJsonPreservesExactBytesAcrossWorldSaveAndStop() throws IOException {
        byte[] original = "{\"broken\":".getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve("QuestDatabase.json"), original);
        QuestDatabaseLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));

        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
            lifecycle.onServerStarted());
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        lifecycle.onWorldSave();
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
        lifecycle.onServerStopping();
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    @Test
    void embeddedUpstreamQuestSettingsRemainSemanticallyExactAcrossWorldSaveAndStop() throws IOException {
        String original = "{\"questDatabase:9\":{},\"questLines:9\":{},"
            + "\"questSettings:10\":{\"unknown:8\":\"opaque\","
            + "\"nested:10\":{\"enabled:1\":1},\"values:9\":{\"0:3\":7,\"1:3\":9}}}";
        Files.writeString(dataDirectory.resolve("QuestDatabase.json"), original, StandardCharsets.UTF_8);
        JsonElement expectedSettings = JsonDocuments.parseObject(original).get("questSettings:10");
        QuestDatabaseLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));

        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.LOADED,
            lifecycle.onServerStarted());
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        lifecycle.onWorldSave();
        assertEquals(expectedSettings, savedEmbeddedQuestSettings());
        lifecycle.onServerStopping();

        assertEquals(expectedSettings, savedEmbeddedQuestSettings());
        assertFalse(Files.exists(dataDirectory.resolve("QuestSettings.json")));
    }

    @Test
    void invalidSemanticRootsPreserveExactBytesAcrossWorldSaveAndStop() throws IOException {
        for (String document : List.of(
            "{\"questLines:9\":{}}",
            "{\"questDatabase:9\":{}}",
            "{\"questDatabase:8\":\"wrong\",\"questLines:9\":{}}",
            "{\"questDatabase:9\":{},\"questLines:8\":\"wrong\"}"
        )) {
            byte[] original = document.getBytes(StandardCharsets.UTF_8);
            Files.write(dataDirectory.resolve("QuestDatabase.json"), original);
            QuestDatabaseLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));

            assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.QUARANTINED,
                lifecycle.onServerStarted());
            QuestDatabase.INSTANCE.createNew(QUEST_ID);
            QuestLineDatabase.INSTANCE.createNew(LINE_ID);
            lifecycle.onWorldSave();
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
            lifecycle.onServerStopping();
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
            assertTrue(QuestDatabase.INSTANCE.isEmpty());
            assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
        }
    }

    @Test
    void unrelatedRuntimeExceptionPropagatesWithoutMalformedBackup() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestDatabase.json"), "{}", StandardCharsets.UTF_8);
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        IllegalStateException injected = new IllegalStateException("injected storage bug");
        QuestDatabaseLifecycle lifecycle = lifecycle(new RuntimeDuringParserReadStorage(dataDirectory, injected));

        assertSame(injected, assertThrows(IllegalStateException.class, lifecycle::onServerStarted));
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
        assertFalse(Files.exists(dataDirectory.resolve("malformed_QuestDatabase.json.json")));
    }

    @Test
    void quarantineCopyFailureClearsDatabasesAndSuppressesLaterWrites() throws IOException {
        for (String document : List.of(
            "{\"questDatabase:9\":{},\"questLines:9\":{},\"mitePortFormat:8\":\"2\"}",
            "{\"questDatabase:9\":{}}",
            "{\"broken\":"
        )) {
            byte[] original = document.getBytes(StandardCharsets.UTF_8);
            Files.write(dataDirectory.resolve("QuestDatabase.json"), original);
            QuestDatabase.INSTANCE.createNew(QUEST_ID);
            QuestLineDatabase.INSTANCE.createNew(LINE_ID);
            QuestDatabaseLifecycle lifecycle = lifecycle(new FailingQuarantineStorage(dataDirectory));

            IOException failure = assertThrows(IOException.class, lifecycle::onServerStarted);
            boolean questsClearedAfterFailure = QuestDatabase.INSTANCE.isEmpty();
            boolean linesClearedAfterFailure = QuestLineDatabase.INSTANCE.isEmpty();
            QuestDatabase.INSTANCE.createNew(QUEST_ID);
            QuestLineDatabase.INSTANCE.createNew(LINE_ID);
            lifecycle.onWorldSave();
            byte[] afterWorldSave = Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json"));
            lifecycle.onServerStopping();
            byte[] afterStop = Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json"));

            assertAll(
                () -> assertEquals("injected quarantine failure", failure.getMessage()),
                () -> assertTrue(questsClearedAfterFailure),
                () -> assertTrue(linesClearedAfterFailure),
                () -> assertArrayEquals(original, afterWorldSave),
                () -> assertArrayEquals(original, afterStop),
                () -> assertTrue(QuestDatabase.INSTANCE.isEmpty()),
                () -> assertTrue(QuestLineDatabase.INSTANCE.isEmpty())
            );
        }
    }

    @Test
    void failedStopRetriesOnceAndCleansBothDatabasesAfterSuccess() throws IOException {
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 1);
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        QuestDatabaseLifecycle lifecycle = lifecycle(storage);
        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertTrue(lifecycle.isRetryOnWorldSave());
        lifecycle.onWorldSave();
        assertFalse(lifecycle.isRetryOnWorldSave());
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
        assertTrue(Files.exists(dataDirectory.resolve("QuestDatabase.json")));
    }

    @Test
    void normalStopSavesOneSharedDocumentFlushesAndClearsBothDatabases() throws IOException {
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        QuestDatabaseLifecycle lifecycle = lifecycle(storage);

        lifecycle.onServerStopping();

        assertEquals(1, storage.flushes);
        assertTrue(Files.exists(dataDirectory.resolve("QuestDatabase.json")));
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    @Test
    void failedRetryStillCleansBothDatabases() throws IOException {
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 2);
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        QuestDatabaseLifecycle lifecycle = lifecycle(storage);
        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertThrows(IOException.class, lifecycle::onWorldSave);
        assertFalse(lifecycle.isRetryOnWorldSave());
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    @Test
    void lifecycleLoadClearsPreviousWorldBothSingletonsWhenDocumentAbsent() throws IOException {
        QuestDatabase.INSTANCE.createNew(QUEST_ID);
        QuestLineDatabase.INSTANCE.createNew(LINE_ID);
        assertEquals(com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore.Outcome.ABSENT,
            lifecycle(new FlushTrackingStorage(dataDirectory)).onServerStarted());
        assertTrue(QuestDatabase.INSTANCE.isEmpty());
        assertTrue(QuestLineDatabase.INSTANCE.isEmpty());
    }

    private QuestDatabaseLifecycle lifecycle(WorldStorage storage) {
        return new QuestDatabaseLifecycle(storage, QuestDatabase.INSTANCE, QuestLineDatabase.INSTANCE, "1.0.0");
    }

    private JsonElement savedEmbeddedQuestSettings() throws IOException {
        return JsonDocuments.parseObject(Files.readString(
            dataDirectory.resolve("QuestDatabase.json"), StandardCharsets.UTF_8)).get("questSettings:10");
    }

    private static class FlushTrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int flushes;
        private FlushTrackingStorage(Path directory) { this.delegate = new DirectoryWorldStorage(directory); }
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
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator) throws IOException { delegate.writeAtomically(path, writer, validator); }
        @Override public Optional<Path> backup(String path) throws IOException { return delegate.backup(path); }
        @Override public void flush() throws IOException { flushes++; delegate.flush(); }
    }

    private static final class FailingWriteStorage extends FlushTrackingStorage {
        private int writesToFail;
        private FailingWriteStorage(Path directory, int writesToFail) { super(directory); this.writesToFail = writesToFail; }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            if (writesToFail-- > 0) throw new IOException("injected write failure");
            super.writeAtomically(path, writer);
        }
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator) throws IOException {
            if (writesToFail-- > 0) throw new IOException("injected write failure");
            super.writeAtomically(path, writer, validator);
        }
    }

    private static final class RuntimeDuringParserReadStorage extends FlushTrackingStorage {
        private final RuntimeException failure;
        private boolean failed;
        private RuntimeDuringParserReadStorage(Path directory, RuntimeException failure) {
            super(directory);
            this.failure = failure;
        }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException {
            if (failed) {
                return super.read(path, reader);
            }
            return super.read(path, input -> reader.read(new FilterInputStream(input) {
                private boolean deliveredFirstByte;

                @Override public int read() throws IOException {
                    if (deliveredFirstByte) {
                        failed = true;
                        throw failure;
                    }
                    deliveredFirstByte = true;
                    return super.read();
                }

                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (deliveredFirstByte) {
                        failed = true;
                        throw failure;
                    }
                    deliveredFirstByte = true;
                    return super.read(bytes, offset, Math.min(length, 1));
                }
            }));
        }
    }

    private static final class FailingQuarantineStorage extends FlushTrackingStorage {
        private FailingQuarantineStorage(Path directory) { super(directory); }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            if (path.equals("malformed_QuestDatabase.json.json")) {
                throw new IOException("injected quarantine failure");
            }
            super.writeAtomically(path, writer);
        }
    }
}
