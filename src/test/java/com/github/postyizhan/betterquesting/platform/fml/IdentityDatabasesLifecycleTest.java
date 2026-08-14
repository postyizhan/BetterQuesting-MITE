package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import com.github.postyizhan.betterquesting.storage.NameCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentityDatabasesLifecycleTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000601");

    @TempDir
    Path dataDirectory;

    @AfterEach
    void clearSingletons() {
        NameCache.INSTANCE.reset();
        LifeDatabase.INSTANCE.reset();
    }

    static Stream<Arguments> datasets() {
        return Stream.of(Dataset.values()).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void validUpstreamLoadAndStampedSaveRoundTrip(Dataset dataset) throws IOException {
        Files.writeString(dataDirectory.resolve(dataset.path), dataset.validDocument, StandardCharsets.UTF_8);
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        Lifecycle lifecycle = dataset.lifecycle(storage);

        assertEquals(JsonDocumentStore.Outcome.LOADED, lifecycle.start());
        assertTrue(dataset.hasExpectedData());
        lifecycle.save(false);

        com.google.gson.JsonObject saved = JsonDocuments.parseObject(Files.readString(
            dataDirectory.resolve(dataset.path), StandardCharsets.UTF_8));
        assertAll(
            () -> assertEquals(List.of(dataset.rootKey), saved.entrySet().stream()
                .map(java.util.Map.Entry::getKey)
                .filter(key -> !key.equals("format:8") && !key.equals("build:8")
                    && !key.equals("mitePortFormat:8"))
                .toList()),
            () -> assertTrue(saved.has("format:8")),
            () -> assertTrue(saved.has("build:8")),
            () -> assertTrue(saved.has("mitePortFormat:8")),
            () -> assertFalse(Files.exists(dataDirectory.resolve(dataset.path + ".tmp")))
        );
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void absentLoadClearsPreviousWorldAndPermitsFirstSave(Dataset dataset) throws IOException {
        dataset.addData();
        Lifecycle lifecycle = dataset.lifecycle(new FlushTrackingStorage(dataDirectory));

        assertEquals(JsonDocumentStore.Outcome.ABSENT, lifecycle.start());
        assertFalse(dataset.hasAnyData());
        dataset.addData();
        lifecycle.save(false);

        assertTrue(Files.exists(dataDirectory.resolve(dataset.path)));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void malformedFutureWrongTypeAndInvalidRootPreserveBytes(Dataset dataset) throws IOException {
        for (String document : List.of(
            "{\"broken\":",
            dataset.futureDocument,
            dataset.wrongMarkerDocument,
            dataset.wrongRootTypeDocument,
            "{}"
        )) {
            byte[] original = document.getBytes(StandardCharsets.UTF_8);
            Files.write(dataDirectory.resolve(dataset.path), original);
            Lifecycle lifecycle = dataset.lifecycle(new FlushTrackingStorage(dataDirectory));

            assertEquals(JsonDocumentStore.Outcome.QUARANTINED, lifecycle.start());
            dataset.addData();
            lifecycle.save(false);
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(dataset.path)));
            lifecycle.stop(false);
            assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(dataset.path)));
            assertFalse(dataset.hasAnyData());
        }
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void ioAndRuntimeExceptionsPropagateWithoutQuarantine(Dataset dataset) throws IOException {
        Files.writeString(dataDirectory.resolve(dataset.path), dataset.validDocument, StandardCharsets.UTF_8);
        IOException io = new IOException("injected read failure");
        assertSame(io, assertThrows(IOException.class,
            () -> dataset.lifecycle(new FailingReadStorage(dataDirectory, io)).start()));
        assertFalse(dataset.hasAnyData());
        assertFalse(Files.exists(dataDirectory.resolve(dataset.quarantinePath())));

        IllegalStateException runtime = new IllegalStateException("injected runtime failure");
        assertSame(runtime, assertThrows(IllegalStateException.class,
            () -> dataset.lifecycle(new RuntimeReadStorage(dataDirectory, runtime)).start()));
        assertFalse(dataset.hasAnyData());
        assertFalse(Files.exists(dataDirectory.resolve(dataset.quarantinePath())));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void quarantineCopyFailureLeavesDataClearedAndWritesDisabled(Dataset dataset) throws IOException {
        byte[] original = dataset.futureDocument.getBytes(StandardCharsets.UTF_8);
        Files.write(dataDirectory.resolve(dataset.path), original);
        Lifecycle lifecycle = dataset.lifecycle(new FailingQuarantineStorage(dataDirectory));

        IOException failure = assertThrows(IOException.class, lifecycle::start);
        boolean clearedAfterFailure = !dataset.hasAnyData();
        dataset.addData();
        lifecycle.save(false);
        byte[] afterSave = Files.readAllBytes(dataDirectory.resolve(dataset.path));
        lifecycle.stop(false);

        assertAll(
            () -> assertEquals("injected quarantine failure", failure.getMessage()),
            () -> assertTrue(clearedAfterFailure),
            () -> assertArrayEquals(original, afterSave),
            () -> assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve(dataset.path))),
            () -> assertFalse(dataset.hasAnyData())
        );
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void deletionCallbacksNeverRecreateDirectory(Dataset dataset) throws IOException {
        dataset.addData();
        Lifecycle lifecycle = dataset.lifecycle(new FlushTrackingStorage(dataDirectory));
        Files.delete(dataDirectory);

        lifecycle.save(true);
        lifecycle.stop(true);

        assertFalse(Files.exists(dataDirectory));
        assertFalse(dataset.hasAnyData());
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void failedStopRetriesOnceOnWorldSaveAndThenClears(Dataset dataset) throws IOException {
        Lifecycle lifecycle = dataset.lifecycle(new FailingWriteStorage(dataDirectory, 1));
        dataset.addData();

        assertThrows(IOException.class, () -> lifecycle.stop(false));
        assertTrue(lifecycle.retrying());
        lifecycle.save(false);

        assertFalse(lifecycle.retrying());
        assertFalse(dataset.hasAnyData());
        dataset.assertSavedPayload(dataDirectory.resolve(dataset.path));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void failedRetryStillClearsAndCannotRetryAgain(Dataset dataset) throws IOException {
        Lifecycle lifecycle = dataset.lifecycle(new FailingWriteStorage(dataDirectory, 2));
        dataset.addData();

        assertThrows(IOException.class, () -> lifecycle.stop(false));
        assertThrows(IOException.class, () -> lifecycle.save(false));

        assertFalse(lifecycle.retrying());
        assertFalse(dataset.hasAnyData());
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void normalStopSavesFlushesAndClears(Dataset dataset) throws IOException {
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        Lifecycle lifecycle = dataset.lifecycle(storage);
        dataset.addData();

        lifecycle.stop(false);

        assertEquals(1, storage.flushes);
        assertTrue(Files.exists(dataDirectory.resolve(dataset.path)));
        assertFalse(dataset.hasAnyData());
    }

    @Test
    void independentFilesDoNotLeakAcrossConsecutiveWorlds() throws IOException {
        Path first = dataDirectory.resolve("first");
        Path second = dataDirectory.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        NameCache.INSTANCE.updateName(PLAYER, "Stale", true);
        LifeDatabase.INSTANCE.setLives(PLAYER, 7);

        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            Dataset.NAMES.lifecycle(new FlushTrackingStorage(first)).start());
        assertFalse(Dataset.NAMES.hasAnyData());
        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            Dataset.LIVES.lifecycle(new FlushTrackingStorage(first)).start());
        assertFalse(Dataset.LIVES.hasAnyData());
        NameCache.INSTANCE.updateName(PLAYER, "Second stale", false);
        LifeDatabase.INSTANCE.setLives(PLAYER, 2);
        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            Dataset.NAMES.lifecycle(new FlushTrackingStorage(second)).start());
        assertEquals(JsonDocumentStore.Outcome.ABSENT,
            Dataset.LIVES.lifecycle(new FlushTrackingStorage(second)).start());

        assertFalse(Dataset.NAMES.hasAnyData());
        assertFalse(Dataset.LIVES.hasAnyData());
        assertFalse(Files.exists(first.resolve(Dataset.NAMES.path)));
        assertFalse(Files.exists(first.resolve(Dataset.LIVES.path)));
    }

    private enum Dataset {
        NAMES(
            "NameCache.json",
            "nameCache:9",
            "{\"nameCache:9\":{\"0:10\":{\"uuid:8\":\"" + PLAYER
                + "\",\"name:8\":\"Alice\",\"isOP:1\":1}}}",
            "{\"nameCache:9\":{},\"mitePortFormat:8\":\"2\"}",
            "{\"nameCache:9\":{},\"mitePortFormat:3\":2}",
            "{\"nameCache:10\":{}}"
        ),
        LIVES(
            "LifeDatabase.json",
            "lifeDatabase:10",
            "{\"lifeDatabase:10\":{\"playerLives:9\":{\"0:10\":{\"uuid:8\":\""
                + PLAYER + "\",\"lives:3\":4}}}}",
            "{\"lifeDatabase:10\":{},\"mitePortFormat:8\":\"2\"}",
            "{\"lifeDatabase:10\":{},\"mitePortFormat:3\":2}",
            "{\"lifeDatabase:9\":{}}"
        );

        private final String path;
        private final String rootKey;
        private final String validDocument;
        private final String futureDocument;
        private final String wrongMarkerDocument;
        private final String wrongRootTypeDocument;

        Dataset(String path, String rootKey, String validDocument, String futureDocument,
            String wrongMarkerDocument, String wrongRootTypeDocument) {
            this.path = path;
            this.rootKey = rootKey;
            this.validDocument = validDocument;
            this.futureDocument = futureDocument;
            this.wrongMarkerDocument = wrongMarkerDocument;
            this.wrongRootTypeDocument = wrongRootTypeDocument;
        }

        private Lifecycle lifecycle(WorldStorage storage) {
            if (this == NAMES) {
                NameCacheLifecycle lifecycle = new NameCacheLifecycle(
                    storage, NameCache.INSTANCE, "build-44");
                return new Lifecycle() {
                    public JsonDocumentStore.Outcome start() throws IOException { return lifecycle.onServerStarted(); }
                    public void save(boolean deleting) throws IOException { lifecycle.onWorldSave(deleting); }
                    public void stop(boolean deleting) throws IOException { lifecycle.onServerStopping(deleting); }
                    public boolean retrying() { return lifecycle.isRetryOnWorldSave(); }
                };
            }
            LifeDatabaseLifecycle lifecycle = new LifeDatabaseLifecycle(
                storage, LifeDatabase.INSTANCE, "build-44");
            return new Lifecycle() {
                public JsonDocumentStore.Outcome start() throws IOException { return lifecycle.onServerStarted(); }
                public void save(boolean deleting) throws IOException { lifecycle.onWorldSave(deleting); }
                public void stop(boolean deleting) throws IOException { lifecycle.onServerStopping(deleting); }
                public boolean retrying() { return lifecycle.isRetryOnWorldSave(); }
            };
        }

        private void addData() {
            if (this == NAMES) NameCache.INSTANCE.updateName(PLAYER, "Alice", true);
            else LifeDatabase.INSTANCE.setLives(PLAYER, 4);
        }

        private boolean hasExpectedData() {
            return this == NAMES
                ? "Alice".equals(NameCache.INSTANCE.getName(PLAYER)) && NameCache.INSTANCE.isOP(PLAYER)
                : LifeDatabase.INSTANCE.getLives(PLAYER) == 4;
        }

        private boolean hasAnyData() {
            return this == NAMES
                ? NameCache.INSTANCE.size() > 0
                : LifeDatabase.INSTANCE.writeToNBT(new net.minecraft.NBTTagCompound(), null)
                    .getTagList("playerLives").tagCount() > 0;
        }

        private void assertSavedPayload(Path file) throws IOException {
            NBTTagCompound root = new NbtJsonCodec().toNbt(
                JsonDocuments.parseObject(Files.readString(file, StandardCharsets.UTF_8)),
                new NBTTagCompound(), true);
            if (this == NAMES) {
                NameCache restored = new NameCache();
                restored.readFromNBT(root.getTagList("nameCache"), false);
                assertEquals("Alice", restored.getName(PLAYER));
                assertTrue(restored.isOP(PLAYER));
                return;
            }
            LifeDatabase restored = new LifeDatabase();
            restored.readFromNBT(root.getCompoundTag("lifeDatabase"), false);
            assertEquals(4, restored.getLives(PLAYER));
        }

        private String quarantinePath() {
            return JsonDocumentStore.quarantineNameFor(path);
        }
    }

    private interface Lifecycle {
        JsonDocumentStore.Outcome start() throws IOException;
        void save(boolean deleting) throws IOException;
        void stop(boolean deleting) throws IOException;
        boolean retrying();
    }

    private static class FlushTrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int flushes;

        private FlushTrackingStorage(Path directory) { delegate = new DirectoryWorldStorage(directory); }
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
            if (writesToFail-- > 0) throw new IOException("injected write failure");
            super.writeAtomically(path, writer, validator);
        }
    }

    private static final class FailingReadStorage extends FlushTrackingStorage {
        private final IOException failure;
        private FailingReadStorage(Path directory, IOException failure) { super(directory); this.failure = failure; }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) throws IOException { throw failure; }
    }

    private static final class RuntimeReadStorage extends FlushTrackingStorage {
        private final RuntimeException failure;
        private RuntimeReadStorage(Path directory, RuntimeException failure) {
            super(directory);
            this.failure = failure;
        }
        @Override public <T> Optional<T> read(String path, InputReader<T> reader) { throw failure; }
    }

    private static final class FailingQuarantineStorage extends FlushTrackingStorage {
        private FailingQuarantineStorage(Path directory) { super(directory); }
        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            if (path.contains("malformed_")) throw new IOException("injected quarantine failure");
            super.writeAtomically(path, writer);
        }
    }
}
