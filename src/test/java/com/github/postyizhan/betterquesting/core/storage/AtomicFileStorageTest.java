package com.github.postyizhan.betterquesting.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileStorageTest {
    private static final Instant FIXED_TIME = Instant.parse("2025-06-07T08:09:10.123Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultStorageWritesEndToEnd() throws IOException {
        Path target = temporaryDirectory.resolve("DefaultStorage.json");

        new AtomicFileStorage().write(target, output -> output.write("default".getBytes(StandardCharsets.UTF_8)));

        assertEquals("default", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("DefaultStorage.json.tmp")));
    }

    @Test
    void writesNewFile() throws IOException {
        Path target = temporaryDirectory.resolve("QuestDatabase.json");

        storage().write(target, output -> output.write("first".getBytes(StandardCharsets.UTF_8)));

        assertEquals("first", Files.readString(target));
        assertEquals(List.of("QuestDatabase.json"), fileNames(temporaryDirectory));
    }

    @Test
    void overwritesFileWithoutImplicitBackup() throws IOException {
        Path target = temporaryDirectory.resolve("QuestDatabase.json");
        Files.writeString(target, "old");

        storage().write(target, output -> output.write("new".getBytes(StandardCharsets.UTF_8)));

        assertEquals("new", Files.readString(target));
        assertEquals(List.of("QuestDatabase.json"), fileNames(temporaryDirectory));
    }

    @Test
    void createsBackupOnlyWhenExplicitlyRequested() throws IOException {
        Path target = temporaryDirectory.resolve("QuestDatabase.json");
        Files.writeString(target, "old");

        Path backup = storage().backup(target).orElseThrow();

        assertEquals("QuestDatabase.json.20250607-080910-123.bak", backup.getFileName().toString());
        assertEquals("old", Files.readString(backup));
    }

    @Test
    void writerFailureDoesNotDamageExistingFile() throws IOException {
        Path target = temporaryDirectory.resolve("QuestDatabase.json");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        IOException failure = assertThrows(IOException.class, () -> storage().write(target, output -> {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("injected failure");
        }));

        assertEquals("injected failure", failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(List.of("QuestDatabase.json"), fileNames(temporaryDirectory));
    }

    @Test
    void backupNamesAreWindowsSafeAndDoNotCollide() throws IOException {
        Path target = temporaryDirectory.resolve("QuestProgress.json");
        Files.writeString(target, "old");
        AtomicFileStorage storage = storage();

        Path first = storage.backup(target).orElseThrow();
        Path second = storage.backup(target).orElseThrow();

        assertEquals("QuestProgress.json.20250607-080910-123.bak", first.getFileName().toString());
        assertEquals("QuestProgress.json.20250607-080910-123-1.bak", second.getFileName().toString());
        assertFalse(first.getFileName().toString().contains(":"));
        assertEquals("old", Files.readString(first));
        assertEquals("old", Files.readString(second));
    }

    @Test
    void createsMissingTargetDirectories() throws IOException {
        Path target = temporaryDirectory.resolve("nested/progress/player.json");

        storage().write(target, output -> output.write("data".getBytes(StandardCharsets.UTF_8)));

        assertTrue(Files.isDirectory(target.getParent()));
        assertEquals("data", Files.readString(target));
    }

    @Test
    void fallsBackWhenAtomicMoveIsUnsupported() throws IOException {
        AtomicBoolean fallbackUsed = new AtomicBoolean();
        AtomicFileStorage.NioMoveStrategy delegate = new AtomicFileStorage.NioMoveStrategy();
        AtomicFileStorage.MoveStrategy moves = new AtomicFileStorage.MoveStrategy() {
            @Override
            public void moveAtomically(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }

            @Override
            public void moveNonAtomically(Path source, Path target) throws IOException {
                fallbackUsed.set(true);
                delegate.moveNonAtomically(source, target);
            }
        };
        AtomicFileStorage storage = new AtomicFileStorage(Clock.fixed(FIXED_TIME, ZoneOffset.UTC), moves);
        Path target = temporaryDirectory.resolve("LifeDatabase.json");

        storage.write(target, output -> output.write("fallback".getBytes(StandardCharsets.UTF_8)));

        assertTrue(fallbackUsed.get());
        assertEquals("fallback", Files.readString(target));
    }

    @Test
    void replacesStaleFixedTemporaryFile() throws IOException {
        Path target = temporaryDirectory.resolve("QuestDatabase.json");
        Files.writeString(temporaryDirectory.resolve("QuestDatabase.json.tmp"), "stale");

        storage().write(target, output -> output.write("current".getBytes(StandardCharsets.UTF_8)));

        assertEquals("current", Files.readString(target));
        assertEquals(List.of("QuestDatabase.json"), fileNames(temporaryDirectory));
    }

    @Test
    void resolvesSafeStoragePath() throws IOException {
        Path root = temporaryDirectory.resolve("betterquesting");

        assertEquals(root.toAbsolutePath().resolve("players/user.json").normalize(),
            StoragePaths.resolveWithin(root, "players/user.json"));
    }

    @Test
    void rejectsEscapingAndInvalidStoragePaths() {
        Path root = temporaryDirectory.resolve("betterquesting");

        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "../outside.json"));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "a/../../outside.json"));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, root.resolve("absolute.json").toString()));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, ""));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "."));
    }

    @Test
    void rejectsWindowsReservedNames() {
        Path root = temporaryDirectory.resolve("betterquesting");

        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "CON"));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "players/nul.json"));
        assertThrows(IOException.class, () -> StoragePaths.resolveWithin(root, "AUX.data.json"));
        assertDoesNotThrow(() -> StoragePaths.resolveWithin(root, "players/console.json"));
    }

    private AtomicFileStorage storage() {
        return new AtomicFileStorage(Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
            new AtomicFileStorage.NioMoveStrategy());
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
