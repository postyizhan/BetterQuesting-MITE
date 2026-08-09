package com.github.postyizhan.betterquesting.core.storage;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

public final class AtomicFileStorage {
    private static final int MAX_BACKUP_COLLISIONS = 100;
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final MoveStrategy moveStrategy;

    public AtomicFileStorage() {
        this(Clock.systemUTC(), new NioMoveStrategy());
    }

    AtomicFileStorage(Clock clock, MoveStrategy moveStrategy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.moveStrategy = Objects.requireNonNull(moveStrategy, "moveStrategy");
    }

    /** Writes without readback validation; see {@link #write(Path, OutputWriter, ReadbackValidator)}. */
    public void write(Path target, OutputWriter writer) throws IOException {
        write(target, writer, ReadbackValidator.NONE);
    }

    /**
     * Writes synchronously through a fixed sibling {@code .tmp} file and replaces the target.
     * Matching upstream's normal save path, this method does not create a backup; callers must
     * invoke {@link #backup(Path)} explicitly for version upgrades or malformed input recovery.
     * After replacement, the parent directory is not fsynced because Java has no cross-platform
     * directory fsync. Following power loss the rename may therefore revert to the old file
     * contents; this is a weaker persistence guarantee, not corruption of the synchronized file.
     *
     * <p>{@code validator} reads the finished temporary file before the replacement, reproducing the
     * check upstream {@code JsonHelper.WriteToFile2} performs at JsonHelper.java:247-255. A
     * validator failure aborts the replacement and deletes the temporary file, so the previous
     * target survives intact. The validator is a parameter rather than a field so this class keeps
     * no format dependency: plain-text callers such as the identity mapping store pass
     * {@link ReadbackValidator#NONE}.
     */
    public void write(Path target, OutputWriter writer, ReadbackValidator validator) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(validator, "validator");

        Path absoluteTarget = target.toAbsolutePath();
        Path directory = absoluteTarget.getParent();
        if (directory == null) {
            throw new IOException("Target has no parent directory: " + target);
        }
        Files.createDirectories(directory);

        Path temporary = directory.resolve(absoluteTarget.getFileName().toString() + ".tmp");
        Files.deleteIfExists(temporary);
        Throwable failure = null;
        boolean replaced = false;
        try {
            writeAndSync(temporary, writer);
            validateReadback(temporary, validator);
            replace(temporary, absoluteTarget);
            replaced = true;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (!replaced) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    public Optional<Path> backup(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path absoluteTarget = target.toAbsolutePath();
        if (!Files.exists(absoluteTarget)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(absoluteTarget)) {
            throw new IOException("Backup source is not a regular file: " + target);
        }

        return Optional.of(copyToNextBackupPath(absoluteTarget));
    }

    private void writeAndSync(Path temporary, OutputWriter writer) throws IOException {
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            writer.write(output);
            output.flush();
            output.getFD().sync();
        }
    }

    private void validateReadback(Path temporary, ReadbackValidator validator) throws IOException {
        if (validator == ReadbackValidator.NONE) {
            return;
        }
        try (InputStream input = Files.newInputStream(temporary)) {
            validator.validate(input);
        }
    }

    private void replace(Path temporary, Path target) throws IOException {
        try {
            moveStrategy.moveAtomically(temporary, target);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // This fallback preserves availability on unsupported filesystems, but the replacement itself is not atomic.
            moveStrategy.moveNonAtomically(temporary, target);
        }
    }

    private Path copyToNextBackupPath(Path target) throws IOException {
        String timestamp = BACKUP_TIMESTAMP.format(clock.instant());
        String fileName = target.getFileName().toString();
        Path directory = target.getParent();
        for (int suffix = 0; suffix < MAX_BACKUP_COLLISIONS; suffix++) {
            String collisionSuffix = suffix == 0 ? "" : "-" + suffix;
            Path candidate = directory.resolve(fileName + "." + timestamp + collisionSuffix + ".bak");
            try {
                return Files.copy(target, candidate, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (FileAlreadyExistsException ignored) {
                // Multiple writes in the same millisecond receive a deterministic numeric suffix.
            }
        }
        throw new IOException("Unable to allocate backup path for " + target
            + " after " + MAX_BACKUP_COLLISIONS + " timestamp collisions");
    }

    @FunctionalInterface
    public interface OutputWriter {
        /**
         * Writes the complete payload. Implementations that wrap {@code output} in a buffered
         * writer must flush that wrapper before returning.
         */
        void write(OutputStream output) throws IOException;
    }

    /**
     * Re-reads a finished temporary file and rejects it before it can replace the target.
     *
     * <p>Implementations must throw to reject; returning normally authorizes the replacement. The
     * stream is closed by the caller.
     */
    @FunctionalInterface
    public interface ReadbackValidator {
        /** Accepts anything, skipping the readback entirely. */
        ReadbackValidator NONE = input -> {
        };

        void validate(InputStream input) throws IOException;
    }

    interface MoveStrategy {
        void moveAtomically(Path source, Path target) throws IOException;

        void moveNonAtomically(Path source, Path target) throws IOException;
    }

    static final class NioMoveStrategy implements MoveStrategy {
        @Override
        public void moveAtomically(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void moveNonAtomically(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
