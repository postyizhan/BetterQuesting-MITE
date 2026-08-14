package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import com.github.postyizhan.betterquesting.storage.QuestSettingsPersistence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestSettingsLifecycleTest {
    @TempDir
    Path dataDirectory;

    @Test
    void lifecycleLoadsSavesAndStopsThroughTheWorldStorageSeam() throws IOException {
        QuestSettings settings = new QuestSettings();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(storage, settings, "1.0.0");

        assertEquals(JsonDocumentStore.Outcome.ABSENT, lifecycle.onServerStarted());
        settings.setProperty(NativeProps.PACK_NAME, "Lifecycle");
        lifecycle.onWorldSave();

        QuestSettings restored = new QuestSettings();
        QuestSettingsLifecycle next = new QuestSettingsLifecycle(storage, restored, "1.0.0");
        assertEquals(JsonDocumentStore.Outcome.LOADED, next.onServerStarted());
        assertEquals("Lifecycle", restored.getProperty(NativeProps.PACK_NAME));

        restored.setProperty(NativeProps.PACK_NAME, "Stopped");
        next.onServerStopping();
        assertEquals(1, storage.flushes);
        QuestSettings afterStop = new QuestSettings();
        assertEquals(JsonDocumentStore.Outcome.LOADED,
            new QuestSettingsLifecycle(new DirectoryWorldStorage(dataDirectory), afterStop, "1.0.0")
            .onServerStarted());
        assertEquals("Stopped", afterStop.getProperty(NativeProps.PACK_NAME));
    }

    @Test
    void successfulStopResetsTheSingletonForTheNextWorld() throws IOException {
        QuestSettings.INSTANCE.setProperty(NativeProps.PACK_NAME, "PreviousWorld");
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(
            new FlushTrackingStorage(dataDirectory), QuestSettings.INSTANCE, "1.0.0");

        lifecycle.onServerStopping();

        assertEquals("", QuestSettings.INSTANCE.getProperty(NativeProps.PACK_NAME));
    }

    @Test
    void deletingStopDoesNotRecreateSettingsAfterWorldDirectoryRemoval() throws IOException {
        QuestSettings settings = new QuestSettings();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(storage, settings, "1.0.0");
        Files.delete(dataDirectory);

        lifecycle.onServerStopping(true);

        assertFalse(Files.exists(dataDirectory));
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
    }

    @Test
    void deletingWorldSaveCallbackBeforeStopDoesNotRecreateSettingsDirectory() throws IOException {
        QuestSettings settings = new QuestSettings();
        FlushTrackingStorage storage = new FlushTrackingStorage(dataDirectory);
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(storage, settings, "1.0.0");
        settings.setProperty(NativeProps.PACK_NAME, "ToDelete");
        lifecycle.onWorldSave();
        Files.delete(dataDirectory.resolve(QuestSettingsPersistence.PATH));
        Files.delete(dataDirectory);

        lifecycle.onWorldSave(true);
        assertFalse(Files.exists(dataDirectory));
        lifecycle.onServerStopping(true);

        assertFalse(Files.exists(dataDirectory));
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
    }

    @Test
    void quarantinedSettingsDisableWorldAndStopSavesForTheSession() throws IOException {
        for (String original : List.of(
            "{\"betterquesting:10\":{\"pack_name:8\":\"Future\"},\"mitePortFormat:8\":\"2\"}",
            "{\"betterquesting:10\":{\"pack_name:8\":\"WrongType\"},\"mitePortFormat:3\":2}",
            "{\"pack_name:8\":"
        )) {
            Files.writeString(dataDirectory.resolve(QuestSettingsPersistence.PATH), original);
            QuestSettings settings = new QuestSettings();
            settings.setProperty(NativeProps.PACK_NAME, "Stale");
            QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(
                new FlushTrackingStorage(dataDirectory), settings, "1.0.0");

            assertEquals(JsonDocumentStore.Outcome.QUARANTINED,
                lifecycle.onServerStarted());
            assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
            settings.setProperty(NativeProps.PACK_NAME, "DefaultsStayActive");

            lifecycle.onWorldSave();
            assertArrayEquals(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(dataDirectory.resolve(QuestSettingsPersistence.PATH)));
            lifecycle.onServerStopping();

            assertArrayEquals(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(dataDirectory.resolve(QuestSettingsPersistence.PATH)));
            assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        }
    }

    @Test
    void failedStopSaveRemainsAvailableForTheWorldSaveRetry() throws IOException {
        QuestSettings settings = new QuestSettings();
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 1);
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(storage, settings, "1.0.0");
        settings.setProperty(NativeProps.PACK_NAME, "Retry");

        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertTrue(lifecycle.isRetryOnWorldSave());

        lifecycle.onWorldSave();

        assertFalse(lifecycle.isRetryOnWorldSave());
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertTrue(Files.exists(dataDirectory.resolve(QuestSettingsPersistence.PATH)));
    }

    @Test
    void failedWorldSaveRetryStillCleansUpTheStoppedWorldSettings() {
        QuestSettings settings = new QuestSettings();
        FailingWriteStorage storage = new FailingWriteStorage(dataDirectory, 2);
        QuestSettingsLifecycle lifecycle = new QuestSettingsLifecycle(storage, settings, "1.0.0");
        settings.setProperty(NativeProps.PACK_NAME, "RetryFailure");

        assertThrows(IOException.class, lifecycle::onServerStopping);
        assertTrue(lifecycle.isRetryOnWorldSave());
        assertThrows(IOException.class, lifecycle::onWorldSave);

        assertFalse(lifecycle.isRetryOnWorldSave());
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
    }

    private static class FlushTrackingStorage implements WorldStorage {
        private final DirectoryWorldStorage delegate;
        private int flushes;

        private FlushTrackingStorage(Path directory) {
            this.delegate = new DirectoryWorldStorage(directory);
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

        @Override public void writeAtomically(String path, OutputWriter writer) throws IOException {
            if (writesToFail > 0) {
                writesToFail--;
                throw new IOException("injected write failure");
            }
            super.writeAtomically(path, writer);
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
}
