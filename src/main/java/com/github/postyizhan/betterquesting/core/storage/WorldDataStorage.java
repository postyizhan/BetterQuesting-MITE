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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Rooted filesystem operations for one world's BetterQuesting data directory. Keeping path
 * resolution at this boundary ensures every operation receives the same traversal checks.
 *
 * <p>All methods must run on the server main thread, or the caller must otherwise prevent
 * concurrent access to the same relative path. {@link AtomicFileStorage#write} uses one fixed
 * {@code <target>.tmp} sibling, while {@link #appendLine} relies on a last-byte guard and truncation
 * rollback; concurrent operations on one target would invalidate both protocols. Upstream
 * serialized file IO through its single-threaded BQThreadedIO queue, which this port does not keep.
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
            T result = reader.read(input);
            if (result == null) {
                throw new IOException("Reader returned null for " + relativePath);
            }
            return Optional.of(result);
        } catch (NoSuchFileException missing) {
            throwIfStorageLayoutIsNotDirectory(path);
            return Optional.empty();
        }
    }

    /**
     * Returns all UTF-8 lines terminated by LF, without their terminators. A final byte fragment
     * without LF is silently discarded; parsing and authenticating each returned audit record is
     * the responsibility of the format-specific caller.
     */
    public List<String> readLines(String relativePath) throws IOException {
        Path path = resolve(relativePath);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (NoSuchFileException missing) {
            throwIfStorageLayoutIsNotDirectory(path);
            return List.of();
        }

        int completeLength = bytes.length;
        while (completeLength > 0 && bytes[completeLength - 1] != '\n') {
            completeLength--;
        }
        if (completeLength == 0) {
            return List.of();
        }

        String complete = new String(bytes, 0, completeLength, StandardCharsets.UTF_8);
        String[] framed = complete.split("\\n", -1);
        List<String> lines = new ArrayList<>(framed.length - 1);
        for (int index = 0; index < framed.length - 1; index++) {
            lines.add(framed[index]);
        }
        return List.copyOf(lines);
    }

    /**
     * Lists regular files with the requested suffix, excluding names containing {@code .DS_Store}
     * or {@code malformed_}. Upstream applied the suffix while enumerating and those two contains
     * checks while reading; this port deliberately merges both stages here because it has no
     * matching read-side skip. Name filters run before the regular-file stat. Unlike upstream,
     * stat failures are treated as non-regular entries and therefore omitted without logging.
     */
    public List<String> list(String relativeDirectory, String suffix) throws IOException {
        Objects.requireNonNull(suffix, "suffix");
        Path directory = resolve(relativeDirectory);
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(suffix)
                        && !name.contains(".DS_Store")
                        && !name.contains("malformed_");
                })
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        } catch (NoSuchFileException missing) {
            // The directory does not exist or disappeared before its stream was opened.
            return List.of();
        } catch (NotDirectoryException notDirectory) {
            // A regular file occupies the requested directory path.
            return List.of();
        }
    }

    public boolean delete(String relativePath) throws IOException {
        Path path = resolve(relativePath);
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new IOException("Delete target is not a regular file: " + relativePath);
        }
        return Files.deleteIfExists(path);
    }

    /**
     * Appends one UTF-8 line with an LF terminator and synchronizes file content and metadata. For
     * a newly created file, its directory entry is not fsynced because Java provides no
     * cross-platform parent-directory fsync. Following power loss that file may therefore be
     * absent even though this method returned successfully.
     *
     * <p>If an existing non-empty file does not end in LF, an extra LF is prepended to the new
     * record. This guard provides framing isolation: a crash fragment cannot fuse with the next
     * record. It cannot identify a fragment such as {@code sec} as invalid after that fragment has
     * become its own line. {@link #readLines} returns raw framed lines, so the audit record format
     * and its parser must be self-validating and reject lines that are not valid records.
     *
     * <p>The last-byte check uses a separate read channel because APPEND and READ cannot be opened
     * together. The check-to-append race is safe only under this class's same-path serialization
     * requirement. An IOException or RuntimeException while writing or forcing rolls the channel
     * back to its size immediately after opening; a rollback failure is suppressed on the original
     * exception.
     */
    public void appendLine(String relativePath, String line) throws IOException {
        Path path = resolve(relativePath);
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Audit line must not contain CR or LF");
        }

        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + path);
        }
        Files.createDirectories(parent);

        boolean needsFramingLf = false;
        if (Files.exists(path) && Files.size(path) > 0) {
            try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer lastByte = ByteBuffer.allocate(1);
                reader.position(reader.size() - 1);
                while (lastByte.hasRemaining()) {
                    reader.read(lastByte);
                }
                needsFramingLf = lastByte.array()[0] != '\n';
            }
        }

        byte[] encoded = ((needsFramingLf ? "\n" : "") + line + "\n")
            .getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            long before = channel.size();
            try {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            } catch (IOException | RuntimeException failure) {
                try {
                    channel.truncate(before);
                } catch (IOException | RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    public void writeAtomically(String relativePath, AtomicFileStorage.OutputWriter writer) throws IOException {
        atomicFiles.write(resolve(relativePath), writer);
    }

    /**
     * Replaces a file only if {@code validator} accepts the finished temporary file. Format
     * knowledge stays with the caller; this class never inspects the payload.
     */
    public void writeAtomically(String relativePath, AtomicFileStorage.OutputWriter writer,
        AtomicFileStorage.ReadbackValidator validator) throws IOException {
        atomicFiles.write(resolve(relativePath), writer, validator);
    }

    public Optional<Path> backup(String relativePath) throws IOException {
        return atomicFiles.backup(resolve(relativePath));
    }

    private void throwIfStorageLayoutIsNotDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        while (parent != null && parent.startsWith(root)) {
            if (Files.exists(parent) && !Files.isDirectory(parent)) {
                throw new IOException("Storage path parent is not a directory: " + parent);
            }
            if (parent.equals(root)) {
                break;
            }
            parent = parent.getParent();
        }
    }

    private Path resolve(String relativePath) throws IOException {
        return StoragePaths.resolveWithin(root, relativePath);
    }

    @FunctionalInterface
    public interface InputReader<T> {
        T read(InputStream input) throws IOException;
    }
}
