package com.github.postyizhan.betterquesting.platform.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * World-scoped storage boundary. Relative paths are validated by the platform implementation
 * before any filesystem operation.
 */
public interface WorldStorage {
    boolean isAvailable();

    /**
     * Returns the BetterQuesting data directory when available. The directory may not exist until
     * the first successful {@link #writeAtomically(String, OutputWriter)} or
     * {@link #appendLine(String, String)} call.
     */
    Optional<Path> getDataDirectory();

    Optional<String> getDisabledReason();

    boolean exists(String relativePath) throws IOException;

    /**
     * Returns empty only when the file does not exist. An existing empty file is passed to the
     * reader, while IO and decoding failures propagate to the caller.
     */
    <T> Optional<T> read(String relativePath, InputReader<T> reader) throws IOException;

    /**
     * Returns every UTF-8 line ending in LF, without the LF. A final fragment without LF is
     * silently discarded; callers remain responsible for validating each returned record's format.
     */
    List<String> readLines(String relativePath) throws IOException;

    /**
     * Lists matching regular files. The implementation deliberately merges upstream's enumeration
     * suffix filter with its read-time contains checks for {@code .DS_Store} and
     * {@code malformed_}, because this boundary has no corresponding read-side skip. A failed
     * regular-file stat is omitted silently, unlike upstream's later logged read failure.
     */
    List<String> list(String relativeDirectory, String suffix) throws IOException;

    boolean delete(String relativePath) throws IOException;

    /**
     * Appends one UTF-8 line followed by LF and synchronizes file content and metadata before
     * returning. For a newly created file, its directory entry is not fsynced because Java has no
     * cross-platform parent-directory fsync; following power loss the file may be absent even
     * though this method returned successfully.
     *
     * <p>When a non-empty target lacks a final LF, the implementation first appends LF. This only
     * isolates framing so a crash fragment cannot fuse with the next record; it cannot recognize
     * that the isolated fragment is invalid. {@link #readLines(String)} returns raw framed lines,
     * and the audit record format/parser must be self-validating. Calls must obey the platform
     * implementation's same-path serialization constraint so the last-byte guard and rollback do
     * not race with another append.
     */
    void appendLine(String relativePath, String line) throws IOException;

    /**
     * Replaces a file without an implicit backup, matching upstream's normal save path. Call
     * {@link #backup(String)} explicitly at version-upgrade or malformed-input recovery points.
     */
    void writeAtomically(String relativePath, OutputWriter writer) throws IOException;

    Optional<Path> backup(String relativePath) throws IOException;

    void flush() throws IOException;

    @FunctionalInterface
    interface InputReader<T> {
        T read(InputStream input) throws IOException;
    }

    @FunctionalInterface
    interface OutputWriter {
        /**
         * Writes the complete payload. Implementations that wrap {@code output} in a buffered
         * writer must flush that wrapper before returning.
         */
        void write(OutputStream output) throws IOException;
    }
}
