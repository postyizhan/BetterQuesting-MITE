package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonDocumentStoreTest {
    @TempDir
    Path dataDirectory;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void savesAndLoadsThroughTheFormatDialect() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("build", "1.0.0");
        NBTTagList entries = new NBTTagList("");
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("n", 7);
        entries.appendTag(entry);
        root.setTag("questDatabase", entries);

        store().save("QuestDatabase.json", root, true);

        assertEquals("{\n\t\"build:8\": \"1.0.0\",\n\t\"questDatabase:9\": {\n\t\t\"0:10\": {"
            + "\n\t\t\t\"n:3\": 7\n\t\t}\n\t}\n}", read("QuestDatabase.json"));

        JsonDocumentStore.LoadResult loaded = store().load("QuestDatabase.json", true);
        assertEquals(JsonDocumentStore.Outcome.LOADED, loaded.outcome());
        assertEquals("1.0.0", loaded.root().getString("build"));
        assertEquals(9, com.github.postyizhan.betterquesting.api.util.NbtCompat
            .getTagId(loaded.root(), "questDatabase"));
    }

    @Test
    void missingFileReportsAbsentWithoutQuarantine() throws IOException {
        JsonDocumentStore.LoadResult loaded = store().load("QuestDatabase.json", true);

        assertEquals(JsonDocumentStore.Outcome.ABSENT, loaded.outcome());
        assertTrue(loaded.root().hasNoTags());
        assertTrue(loaded.quarantinePath().isEmpty());
        assertEquals(List.of(), listNames());
    }

    @Test
    void malformedFileIsQuarantinedUnderUpstreamsDoubledExtension() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestDatabase.json"), "{\"truncated\":");

        JsonDocumentStore.LoadResult loaded = store().load("QuestDatabase.json", true);

        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, loaded.outcome());
        assertTrue(loaded.root().hasNoTags());
        assertEquals("malformed_QuestDatabase.json.json", loaded.quarantinePath().orElseThrow());
        // The original is preserved, matching upstream's copy-not-move recovery.
        assertEquals(List.of("QuestDatabase.json", "malformed_QuestDatabase.json.json"), listNames());
        assertEquals("{\"truncated\":", read("malformed_QuestDatabase.json.json"));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void quarantineNameKeepsTheSubdirectoryAndIsExcludedFromEnumeration() throws IOException {
        assertEquals("QuestProgress/malformed_abc.json.json",
            JsonDocumentStore.quarantineNameFor("QuestProgress/abc.json"));

        Path progress = dataDirectory.resolve("QuestProgress");
        Files.createDirectories(progress);
        Files.writeString(progress.resolve("abc.json"), "not json at all");

        JsonDocumentStore.LoadResult loaded = store().load("QuestProgress/abc.json", true);

        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, loaded.outcome());
        assertTrue(Files.exists(progress.resolve("malformed_abc.json.json")));
        // WorldDataStorage.list already excludes names containing malformed_, so the quarantined
        // copy is never re-enumerated as a player progress file.
        assertEquals(List.of("abc.json"), storage().list("QuestProgress", ".json"));
    }

    @Test
    void writeIsRejectedWhenTheProducedDocumentCannotBeParsedBack() throws IOException {
        Path target = dataDirectory.resolve("QuestDatabase.json");
        Files.writeString(target, "{\"good\":1}");

        assertThrows(MalformedJsonDocumentException.class, () -> storage().writeAtomically(
            "QuestDatabase.json",
            output -> output.write("{\"broken\":".getBytes(StandardCharsets.UTF_8)),
            JsonDocuments::parseObject));

        assertEquals("{\"good\":1}", Files.readString(target));
        assertEquals(List.of("QuestDatabase.json"), listNames());
    }

    @Test
    void writeSucceedsAndLeavesNoTemporaryFileWhenValidationPasses() throws IOException {
        storage().writeAtomically(
            "QuestDatabase.json",
            output -> output.write("{\"ok\":1}".getBytes(StandardCharsets.UTF_8)),
            JsonDocuments::parseObject);

        assertEquals("{\"ok\":1}", read("QuestDatabase.json"));
        assertEquals(List.of("QuestDatabase.json"), listNames());
    }

    private JsonDocumentStore store() {
        return new JsonDocumentStore(storage(), new NbtJsonCodec(warnings::add), warnings::add);
    }

    private DirectoryWorldStorage storage() {
        return new DirectoryWorldStorage(dataDirectory);
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(dataDirectory.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private List<String> listNames() throws IOException {
        try (var entries = Files.list(dataDirectory)) {
            return entries
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }
    }
}
