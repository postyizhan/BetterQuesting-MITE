package com.github.postyizhan.betterquesting.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDataStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsFileExistence() throws IOException {
        WorldDataStorage storage = storage();
        Files.createDirectories(temporaryDirectory.resolve("players"));
        Files.writeString(temporaryDirectory.resolve("players/alice.dat"), "data");

        assertTrue(storage.exists("players/alice.dat"));
        assertFalse(storage.exists("players/bob.dat"));
    }

    @Test
    void listsMissingDirectoryAsEmpty() throws IOException {
        assertEquals(List.of(), storage().list("players", ".json"));
    }

    @Test
    void listsOnlyRegularFilesWithRequestedSuffix() throws IOException {
        Path players = Files.createDirectories(temporaryDirectory.resolve("players"));
        Files.writeString(players.resolve("alice.json"), "{}");
        Files.writeString(players.resolve("alice.json.bak"), "backup");
        Files.writeString(players.resolve("alice.json.tmp"), "temporary");
        Files.writeString(players.resolve("notes.txt"), "notes");
        Files.createDirectories(players.resolve("directory.json"));

        assertEquals(List.of("alice.json"), storage().list("players", ".json"));
    }

    @Test
    void distinguishesMissingFileFromEmptyFile() throws IOException {
        WorldDataStorage storage = storage();
        Optional<byte[]> missing = storage.read("missing.dat", input -> input.readAllBytes());
        Files.write(temporaryDirectory.resolve("empty.dat"), new byte[0]);
        Optional<byte[]> empty = storage.read("empty.dat", input -> input.readAllBytes());

        assertTrue(missing.isEmpty());
        assertTrue(empty.isPresent());
        assertArrayEquals(new byte[0], empty.orElseThrow());
    }

    @Test
    void doesNotConvertReadFailureToEmpty() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("directory"));

        assertThrows(IOException.class,
            () -> storage().read("directory", input -> input.readAllBytes()));
    }

    @Test
    void appendsLinesInOrderWithPortableTerminators() throws IOException {
        WorldDataStorage storage = storage();

        storage.appendLine("audit/events.log", "first");
        storage.appendLine("audit/events.log", "second");

        byte[] actual = Files.readAllBytes(temporaryDirectory.resolve("audit/events.log"));
        assertArrayEquals("first\nsecond\n".getBytes(StandardCharsets.UTF_8), actual);
        assertFalse(new String(actual, StandardCharsets.UTF_8).contains("\r\n"));
    }

    @Test
    void rejectsEmbeddedLineTerminators() {
        WorldDataStorage storage = storage();

        assertThrows(IllegalArgumentException.class,
            () -> storage.appendLine("audit.log", "forged\nrecord"));
        assertThrows(IllegalArgumentException.class,
            () -> storage.appendLine("audit.log", "forged\rrecord"));
    }

    @Test
    void appendPreservesEveryExistingByteIncludingIncompleteTail() throws IOException {
        Path audit = temporaryDirectory.resolve("audit.log");
        byte[] original = "complete\nincomplete".getBytes(StandardCharsets.UTF_8);
        Files.write(audit, original);

        storage().appendLine("audit.log", "next");

        byte[] actual = Files.readAllBytes(audit);
        assertArrayEquals(concat(original, "next\n".getBytes(StandardCharsets.UTF_8)), actual);
    }

    @Test
    void deleteReportsWhetherAFileWasRemoved() throws IOException {
        Path target = temporaryDirectory.resolve("players/alice.dat");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "data");
        WorldDataStorage storage = storage();

        assertTrue(storage.delete("players/alice.dat"));
        assertFalse(Files.exists(target));
        assertFalse(storage.delete("players/alice.dat"));
    }

    @Test
    void rejectsTraversalForEveryNewOperation() {
        WorldDataStorage storage = storage();

        assertThrows(IOException.class, () -> storage.exists("../outside"));
        assertThrows(IOException.class, () -> storage.read("../outside", input -> input.readAllBytes()));
        assertThrows(IOException.class, () -> storage.list("../outside", ".json"));
        assertThrows(IOException.class, () -> storage.delete("../outside"));
        assertThrows(IOException.class, () -> storage.appendLine("../outside", "line"));
    }

    @Test
    void rejectsAbsolutePathsForEveryNewOperation() {
        WorldDataStorage storage = storage();
        String absolute = temporaryDirectory.resolve("absolute").toAbsolutePath().toString();

        assertThrows(IOException.class, () -> storage.exists(absolute));
        assertThrows(IOException.class, () -> storage.read(absolute, input -> input.readAllBytes()));
        assertThrows(IOException.class, () -> storage.list(absolute, ".json"));
        assertThrows(IOException.class, () -> storage.delete(absolute));
        assertThrows(IOException.class, () -> storage.appendLine(absolute, "line"));
    }

    private WorldDataStorage storage() {
        return new WorldDataStorage(temporaryDirectory);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
