package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartyManagerLifecycleTest {
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000501");

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void clearSingleton() {
        PartyManager.INSTANCE.reset();
    }

    @Test
    void malformedFutureWrongTypeAndInvalidRootPreserveBytesAcrossSaveAndStop() throws IOException {
        for (String document : List.of(
            "{\"broken\":",
            "{\"parties:9\":{},\"mitePortFormat:8\":\"2\"}",
            "{\"parties:9\":{},\"mitePortFormat:3\":2}",
            "{\"parties:8\":\"wrong\"}",
            "{}"
        )) {
            byte[] original = document.getBytes(StandardCharsets.UTF_8);
            Files.write(dataDirectory.resolve("QuestingParties.json"), original);
            PartyManagerLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));

            assertEquals(JsonDocumentStore.Outcome.QUARANTINED, lifecycle.onServerStarted());
            addParty(1);
            lifecycle.onWorldSave(false);
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestingParties.json")));
            lifecycle.onServerStopping(false);
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestingParties.json")));
            assertEquals(0, PartyManager.INSTANCE.size());
        }
    }

    @Test
    void unrelatedIoAndRuntimeFailuresPropagateWithoutMalformedBackup() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestingParties.json"),
            "{\"parties:9\":{}}", StandardCharsets.UTF_8);
        IOException io = new IOException("injected read failure");
        PartyManagerLifecycle ioLifecycle = lifecycle(new FailingReadStorage(dataDirectory, io));

        assertSame(io, assertThrows(IOException.class, ioLifecycle::onServerStarted));
        assertEquals(0, PartyManager.INSTANCE.size());
        assertFalse(Files.exists(dataDirectory.resolve("malformed_QuestingParties.json.json")));

        IllegalStateException runtime = new IllegalStateException("injected parser runtime");
        PartyManagerLifecycle runtimeLifecycle = lifecycle(
            new RuntimeDuringParserReadStorage(dataDirectory, runtime));
        assertSame(runtime, assertThrows(IllegalStateException.class, runtimeLifecycle::onServerStarted));
        assertEquals(0, PartyManager.INSTANCE.size());
        assertFalse(Files.exists(dataDirectory.resolve("malformed_QuestingParties.json.json")));
    }

    @Test
    void quarantineCopyFailureLeavesSingletonClearedAndWritesDisabled() throws IOException {
        byte[] original = "{\"parties:9\":{},\"mitePortFormat:8\":\"2\"}"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve("QuestingParties.json"), original);
        PartyManagerLifecycle lifecycle = lifecycle(new FailingQuarantineStorage(dataDirectory));

        IOException failure = assertThrows(IOException.class, lifecycle::onServerStarted);
        boolean clearedAfterFailure = PartyManager.INSTANCE.size() == 0;
        addParty(2);
        lifecycle.onWorldSave(false);
        byte[] afterSave = Files.readAllBytes(dataDirectory.resolve("QuestingParties.json"));
        lifecycle.onServerStopping(false);

        assertAll(
            () -> assertEquals("injected quarantine failure", failure.getMessage()),
            () -> assertTrue(clearedAfterFailure),
            () -> assertArrayEquals(original, afterSave),
            () -> assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("QuestingParties.json"))),
            () -> assertEquals(0, PartyManager.INSTANCE.size())
        );
    }

    @Test
    void deletionCallbacksNeverRecreateDirectories() throws IOException {
        addParty(3);
        PartyManagerLifecycle lifecycle = lifecycle(new FlushTrackingStorage(dataDirectory));
        Files.delete(dataDirectory);

        lifecycle.onWorldSave(true);
        assertFalse(Files.exists(dataDirectory));
        lifecycle.onServerStopping(true);

        assertFalse(Files.exists(dataDirectory));
        assertEquals(0, PartyManager.INSTANCE.size());
    }

    @Test
    void failedStopRetriesOnceOnWorldSaveAndThenClears() throws IOException {
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 1);
        PartyManagerLifecycle lifecycle = lifecycle(storage);
        addParty(4);

        assertThrows(IOException.class, () -> lifecycle.onServerStopping(false));
        assertTrue(lifecycle.isRetryOnWorldSave());
        lifecycle.onWorldSave(false);

        assertFalse(lifecycle.isRetryOnWorldSave());
        assertEquals(0, PartyManager.INSTANCE.size());
        assertTrue(Files.exists(dataDirectory.resolve("QuestingParties.json")));
    }

    @Test
    void failedRetryStillClearsAndCannotRetryAgain() throws IOException {
        PartyManagerLifecycle lifecycle = lifecycle(new FailingWriteStorage(dataDirectory, 2));
        addParty(5);

        assertThrows(IOException.class, () -> lifecycle.onServerStopping(false));
        assertThrows(IOException.class, () -> lifecycle.onWorldSave(false));

        assertFalse(lifecycle.isRetryOnWorldSave());
        assertEquals(0, PartyManager.INSTANCE.size());
    }

    @Test
    void normalStopFlushesSavesAndClears() throws IOException {
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        PartyManagerLifecycle lifecycle = lifecycle(storage);
        addParty(6);

        lifecycle.onServerStopping(false);

        assertEquals(1, storage.flushes);
        assertTrue(Files.exists(dataDirectory.resolve("QuestingParties.json")));
        assertEquals(0, PartyManager.INSTANCE.size());
    }

    @Test
    void consecutiveAbsentWorldsNeverLeakSingletonParties() throws IOException {
        Path firstWorld = dataDirectory.resolve("first");
        Path secondWorld = dataDirectory.resolve("second");
        Files.createDirectories(firstWorld);
        Files.createDirectories(secondWorld);

        addParty(7);
        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            lifecycle(new FlushTrackingStorage(firstWorld)).onServerStarted());
        assertEquals(0, PartyManager.INSTANCE.size());
        addParty(8);
        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            lifecycle(new FlushTrackingStorage(secondWorld)).onServerStarted());

        assertEquals(0, PartyManager.INSTANCE.size());
        assertFalse(Files.exists(firstWorld.resolve("QuestingParties.json")));
        assertFalse(Files.exists(secondWorld.resolve("QuestingParties.json")));
    }

    private PartyManagerLifecycle lifecycle(WorldStorage storage) {
        return new PartyManagerLifecycle(storage, PartyManager.INSTANCE, "build-43");
    }

    private static void addParty(int id) {
        PartyManager.INSTANCE.createNew(id).setStatus(MEMBER, EnumPartyStatus.OWNER);
    }

    private static class FlushTrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int flushes;

        private FlushTrackingStorage(Path directory) {
            delegate = new DirectoryWorldStorage(directory);
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
        @Override public void writeAtomically(String path, OutputWriter writer, ReadbackValidator validator)
            throws IOException {
            delegate.writeAtomically(path, writer, validator);
        }
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
            if (writesToFail > 0) {
                writesToFail--;
                throw new IOException("injected write failure");
            }
            super.writeAtomically(path, writer, validator);
        }
    }

    private static final class FailingReadStorage extends FlushTrackingStorage {
        private final IOException failure;

        private FailingReadStorage(Path directory, IOException failure) {
            super(directory);
            this.failure = failure;
        }

        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException {
            throw failure;
        }
    }

    private static final class RuntimeDuringParserReadStorage extends FlushTrackingStorage {
        private final RuntimeException failure;

        private RuntimeDuringParserReadStorage(Path directory, RuntimeException failure) {
            super(directory);
            this.failure = failure;
        }

        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException {
            return super.read(path, input -> reader.read(new FilterInputStream(input) {
                @Override public int read() { throw failure; }
                @Override public int read(byte[] bytes, int offset, int length) { throw failure; }
            }));
        }
    }

    private static final class FailingQuarantineStorage extends FlushTrackingStorage {
        private FailingQuarantineStorage(Path directory) {
            super(directory);
        }

        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            if (path.contains("malformed_")) throw new IOException("injected quarantine failure");
            super.writeAtomically(path, writer);
        }
    }
}
