package com.github.postyizhan.betterquesting.core.storage;

import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link WorldStorage} over a plain directory, with no Minecraft dependency.
 *
 * <p>This is the single adapter between {@link WorldDataStorage} and the {@code WorldStorage}
 * boundary. The platform implementation delegates here so tests exercise the same code the game
 * runs, rather than a parallel copy.
 *
 * <p>Always available: the caller already chose a directory, so there is no disabled branch. The
 * same-path serialization requirement documented on {@link WorldDataStorage} applies unchanged.
 */
public final class DirectoryWorldStorage implements WorldStorage {
    private final WorldDataStorage storage;
    private final Path dataDirectory;

    public DirectoryWorldStorage(Path dataDirectory) {
        this(dataDirectory, new AtomicFileStorage());
    }

    public DirectoryWorldStorage(Path dataDirectory, AtomicFileStorage atomicFiles) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
            .toAbsolutePath().normalize();
        this.storage = new WorldDataStorage(this.dataDirectory, atomicFiles);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<Path> getDataDirectory() {
        return Optional.of(dataDirectory);
    }

    @Override
    public Optional<String> getDisabledReason() {
        return Optional.empty();
    }

    @Override
    public boolean exists(String relativePath) throws IOException {
        return storage.exists(relativePath);
    }

    @Override
    public <T> Optional<T> read(String relativePath, InputReader<T> reader) throws IOException {
        return storage.read(relativePath, reader::read);
    }

    @Override
    public List<String> readLines(String relativePath) throws IOException {
        return storage.readLines(relativePath);
    }

    @Override
    public List<String> list(String relativeDirectory, String suffix) throws IOException {
        return storage.list(relativeDirectory, suffix);
    }

    @Override
    public boolean delete(String relativePath) throws IOException {
        return storage.delete(relativePath);
    }

    @Override
    public void appendLine(String relativePath, String line) throws IOException {
        storage.appendLine(relativePath, line);
    }

    @Override
    public void writeAtomically(String relativePath, OutputWriter writer) throws IOException {
        storage.writeAtomically(relativePath, writer::write);
    }

    @Override
    public Optional<Path> backup(String relativePath) throws IOException {
        return storage.backup(relativePath);
    }

    /** Writes complete synchronously, so there is no queue to drain. */
    @Override
    public void flush() {
    }
}
