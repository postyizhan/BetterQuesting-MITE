package com.github.postyizhan.betterquesting.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Rooted filesystem operations for one world's BetterQuesting data directory. Keeping path
 * resolution at this boundary ensures every operation receives the same traversal checks.
 */
public final class WorldDataStorage {
    private final Path root;
    private final AtomicFileStorage atomicFiles;

    public WorldDataStorage(Path root) {
        this(root, new AtomicFileStorage());
    }

    public WorldDataStorage(Path root, AtomicFileStorage atomicFiles) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.atomicFiles = Objects.requireNonNull(atomicFiles, "atomicFiles");
    }

    public boolean exists(String relativePath) throws IOException {
        return Files.exists(resolve(relativePath));
    }

    public <T> Optional<T> read(String relativePath, InputReader<T> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Path path = resolve(relativePath);
        try (InputStream input = Files.newInputStream(path)) {
            return Optional.of(Objects.requireNonNull(reader.read(input), "reader result"));
        } catch (NoSuchFileException | NotDirectoryException missing) {
            return Optional.empty();
        }
    }

    public List<String> list(String relativeDirectory, String suffix) throws IOException {
        Objects.requireNonNull(suffix, "suffix");
        Path directory = resolve(relativeDirectory);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(suffix))
                .sorted()
                .toList();
        } catch (NoSuchFileException | NotDirectoryException missing) {
            // The directory may disappear between the type check and opening its stream.
            return List.of();
        }
    }

    public boolean delete(String relativePath) throws IOException {
        return Files.deleteIfExists(resolve(relativePath));
    }

    /**
     * Appends one UTF-8 line with a fixed LF terminator and forces it to stable storage. The append
     * is deliberately not implemented as atomic replacement: already-written audit bytes must
     * survive a later crash. If the process dies between the single write and {@code force}, the
     * file may end in an incomplete line; readers must discard that trailing fragment rather than
     * treating the whole file as malformed.
     */
    public void appendLine(String relativePath, String line) throws IOException {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Audit line must not contain CR or LF");
        }

        Path path = resolve(relativePath);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            int written = channel.write(ByteBuffer.wrap(encoded));
            if (written != encoded.length) {
                throw new IOException("Incomplete append to " + path + ": wrote " + written
                    + " of " + encoded.length + " bytes");
            }
            channel.force(true);
        }
    }

    public void writeAtomically(String relativePath, AtomicFileStorage.OutputWriter writer) throws IOException {
        atomicFiles.write(resolve(relativePath), writer);
    }

    public Optional<Path> backup(String relativePath) throws IOException {
        return atomicFiles.backup(resolve(relativePath));
    }

    private Path resolve(String relativePath) throws IOException {
        return StoragePaths.resolveWithin(root, relativePath);
    }

    @FunctionalInterface
    public interface InputReader<T> {
        T read(InputStream input) throws IOException;
    }
}
