package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.io.IOException;
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

    private static final class FlushTrackingStorage implements WorldStorage {
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
}
