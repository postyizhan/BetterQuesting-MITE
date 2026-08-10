package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Corruption corpus for the JSON storage layer.
 *
 * <p>Locks two properties for every damaged input. At the parse layer the bytes must raise
 * {@link MalformedJsonDocumentException} rather than yielding a partial or empty document; at the
 * store layer they must produce {@link JsonDocumentStore.Outcome#QUARANTINED} with an empty root and
 * an untouched original. The second property is what stops a truncated database from being read as
 * "no quests" and then written back over the good file on the next save.
 *
 * <p>Non-object roots are deliberately rejected rather than treated as an empty document; see
 * {@code JsonDocuments.parseObject}. That decision is asserted here so a future "return empty on
 * null root" simplification fails loudly.
 */
class MalformedJsonFixtureTest {
    private static final String SYNTAX = "Malformed JSON document";
    private static final String ROOT = "Expected a JSON object at the document root but found ";

    @TempDir
    Path dataDirectory;

    private final List<String> warnings = new ArrayList<>();

    /**
     * The corpus, paired with the message that proves <em>which</em> rejection path ran. A syntax
     * failure and a well-formed-but-wrong-root document are different bugs on disk, and several of
     * these land on the side a reader would not guess: Gson 2.2.2 parses in lenient mode, so an
     * all-NUL file and an unquoted bare word both arrive as primitives instead of syntax errors.
     */
    static Stream<Arguments> malformedFixtures() {
        return Stream.of(
            // Structural damage.
            Arguments.of("broken-syntax.json", SYNTAX),
            Arguments.of("trailing-comma.json", SYNTAX),
            // Truncation: mid-object, mid-string, mid-array, and immediately after the root brace.
            Arguments.of("truncated-mid-object.json", SYNTAX),
            Arguments.of("truncated-mid-string.json", SYNTAX),
            Arguments.of("truncated-mid-array.json", SYNTAX),
            Arguments.of("truncated-open-brace.json", SYNTAX),
            // Torn writes: a partial or complete object followed by unwritten sector padding.
            Arguments.of("nul-padded-truncated.json", SYNTAX),
            Arguments.of("nul-padded-complete-object.json", SYNTAX),
            // Extra content after a complete root.
            Arguments.of("trailing-garbage.json", SYNTAX),
            Arguments.of("two-root-objects.json", SYNTAX),
            // Non-object roots.
            Arguments.of("root-array.json", ROOT + "an array"),
            Arguments.of("root-bare-string.json", ROOT + "a primitive"),
            Arguments.of("root-bare-number.json", ROOT + "a primitive"),
            Arguments.of("root-unquoted-word.json", ROOT + "a primitive"),
            Arguments.of("all-nul.json", ROOT + "a primitive"),
            Arguments.of("root-json-null.json", ROOT + "null"),
            // Nothing at all. Upstream returned null here and let the caller default to an empty
            // database; this port refuses instead.
            Arguments.of("empty.json", ROOT + "null"),
            Arguments.of("whitespace-only.json", ROOT + "null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFixtures")
    void parseObjectRejectsTheFixture(String fixture, String expectedMessage) {
        byte[] document = MalformedJsonFixtures.bytes(MalformedJsonFixtures.MALFORMED, fixture);

        MalformedJsonDocumentException failure = assertThrows(MalformedJsonDocumentException.class,
            () -> JsonDocuments.parseObject(new ByteArrayInputStream(document)));

        assertTrue(failure.getMessage().startsWith(expectedMessage),
            () -> fixture + " reported: " + failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFixtures")
    void loadQuarantinesTheFixtureAndKeepsTheEvidence(String fixture, String expectedMessage)
        throws IOException {
        byte[] document = MalformedJsonFixtures.bytes(MalformedJsonFixtures.MALFORMED, fixture);
        Files.write(dataDirectory.resolve("QuestDatabase.json"), document);

        JsonDocumentStore.LoadResult loaded = store().load("QuestDatabase.json", true);

        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, loaded.outcome());
        assertTrue(loaded.root().hasNoTags(), "a rejected document must not yield partial tags");
        assertEquals("malformed_QuestDatabase.json.json", loaded.quarantinePath().orElseThrow());
        // The copy is byte-identical, including NUL padding and any BOM, so an administrator sees
        // exactly what was on disk.
        assertArrayEquals(document,
            Files.readAllBytes(dataDirectory.resolve("malformed_QuestDatabase.json.json")));
        assertArrayEquals(document, Files.readAllBytes(dataDirectory.resolve("QuestDatabase.json")));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("QuestDatabase.json")),
            () -> "expected a diagnostic naming the file, got " + warnings);
    }

    @Test
    void everyMalformedFixtureFileIsCovered() {
        List<String> declared = malformedFixtures()
            .map(arguments -> (String) arguments.get()[0])
            .sorted()
            .toList();

        assertEquals(MalformedJsonFixtures.names(MalformedJsonFixtures.MALFORMED), declared);
    }

    private JsonDocumentStore store() {
        return new JsonDocumentStore(storage(), new NbtJsonCodec(warnings::add), warnings::add);
    }

    private DirectoryWorldStorage storage() {
        return new DirectoryWorldStorage(dataDirectory);
    }
}
