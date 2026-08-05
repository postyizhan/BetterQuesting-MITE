package com.github.postyizhan.betterquesting.core.storage;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class StoragePaths {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private StoragePaths() {
    }

    /**
     * Resolves a non-empty relative path below {@code root} using lexical normalization.
     * This does not resolve symbolic links. If paths later come from client packets, callers
     * must replace this boundary with an allowlist or a {@code toRealPath()} based check.
     */
    public static Path resolveWithin(Path root, String relative) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relative, "relative");
        if (relative.isEmpty()) {
            throw new IOException("Storage path must not be empty");
        }

        final Path relativePath;
        try {
            relativePath = root.getFileSystem().getPath(relative);
        } catch (InvalidPathException invalid) {
            throw new IOException("Invalid storage path: " + relative, invalid);
        }
        if (relativePath.isAbsolute()) {
            throw new IOException("Storage path must be relative: " + relative);
        }

        Path normalizedRelative = relativePath.normalize();
        if (normalizedRelative.getNameCount() == 0 || normalizedRelative.toString().isEmpty()
            || normalizedRelative.toString().equals(".")) {
            throw new IOException("Storage path must identify a file: " + relative);
        }
        for (Path segment : normalizedRelative) {
            rejectWindowsReservedName(segment.toString(), relative);
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalizedRelative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Storage path escapes the BetterQuesting data directory: " + relative);
        }
        return resolved;
    }

    private static void rejectWindowsReservedName(String segment, String relative) throws IOException {
        String name = segment;
        int extension = name.indexOf('.');
        if (extension >= 0) {
            name = name.substring(0, extension);
        }
        if (WINDOWS_RESERVED_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
            throw new IOException("Storage path contains a Windows reserved name: " + relative);
        }
    }
}
