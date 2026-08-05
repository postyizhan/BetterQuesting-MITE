package com.github.postyizhan.betterquesting.platform.api;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pure write-side world storage boundary. Read, exists, list, and delete operations required by
 * upstream's loading path are intentionally deferred to a later batch.
 */
public interface WorldStorage {
    boolean isAvailable();

    /**
     * Returns the BetterQuesting data directory when available. The directory may not exist until
     * the first successful {@link #writeAtomically(String, OutputWriter)} call.
     */
    Optional<Path> getDataDirectory();

    Optional<String> getDisabledReason();

    /**
     * Replaces a file without an implicit backup, matching upstream's normal save path. Call
     * {@link #backup(String)} explicitly at version-upgrade or malformed-input recovery points.
     */
    void writeAtomically(String relativePath, OutputWriter writer) throws IOException;

    Optional<Path> backup(String relativePath) throws IOException;

    void flush() throws IOException;

    @FunctionalInterface
    interface OutputWriter {
        /**
         * Writes the complete payload. Implementations that wrap {@code output} in a buffered
         * writer must flush that wrapper before returning.
         */
        void write(OutputStream output) throws IOException;
    }
}
