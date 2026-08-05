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
     * the first successful {@link #writeAtomically(String, OutputWriter)} call.
     */
    Optional<Path> getDataDirectory();

    Optional<String> getDisabledReason();

    boolean exists(String relativePath) throws IOException;

    /**
     * Returns empty only when the file does not exist. An existing empty file is passed to the
     * reader, while IO and decoding failures propagate to the caller.
     */
    <T> Optional<T> read(String relativePath, InputReader<T> reader) throws IOException;

    List<String> list(String relativeDirectory, String suffix) throws IOException;

    boolean delete(String relativePath) throws IOException;

    /**
     * Appends one line followed by LF and synchronizes it before returning. A process crash between
     * the single write and synchronization can leave an incomplete final line; readers of these
     * append logs must discard that trailing fragment without rejecting earlier complete lines.
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
