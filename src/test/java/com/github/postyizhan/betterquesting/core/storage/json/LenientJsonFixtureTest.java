package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks what Gson 2.2.2's lenient parser <em>accepts</em>, which is the side a reader would not
 * guess. These documents look damaged, so the risk is the opposite of the malformed fixtures: no
 * quarantine happens and the file is loaded, sometimes losing a value on the way.
 *
 * <p>Every fact here was measured against the real Gson 2.2.2 on the runtime classpath, not
 * inferred. An earlier assertion that the parser rejects a UTF-8 BOM was wrong and is why
 * {@code bom-prefixed.json} lives in {@code fixtures/lenient} rather than {@code fixtures/malformed}.
 */
class LenientJsonFixtureTest {
    @Test
    void aUtf8BomIsToleratedSoAnEditorSavedDatabaseStillLoads() throws IOException {
        JsonObject parsed = parse("bom-prefixed.json");

        // Accepting this matters: Windows editors add a BOM routinely, and rejecting it would
        // quarantine a semantically intact database and discard the administrator's edit.
        assertEquals("3.1.0", parsed.get("build:8").getAsString());
        assertEquals(1, count(parsed));
    }

    @Test
    void singleQuotesUnquotedKeysAndCommentsAreAllAccepted() throws IOException {
        JsonObject parsed = parse("unquoted-keys-and-comments.json");

        // A hand-edited file is indistinguishable from a well-formed one at this boundary.
        assertEquals("3.1.0", parsed.get("build").getAsString());
        assertTrue(parsed.has("questID:3"), () -> "expected the NaN member to survive parsing");
        assertEquals(2, count(parsed));
    }

    @Test
    void everyLenientFixtureFileIsCovered() {
        assertEquals(List.of("bom-prefixed.json", "unquoted-keys-and-comments.json"),
            MalformedJsonFixtures.names(MalformedJsonFixtures.LENIENT));
    }

    private static JsonObject parse(String name) throws IOException {
        return JsonDocuments.parseObject(
            new ByteArrayInputStream(MalformedJsonFixtures.bytes(MalformedJsonFixtures.LENIENT, name)));
    }

    private static int count(JsonObject object) {
        int members = 0;
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> ignored : object.entrySet()) {
            members++;
        }
        return members;
    }
}
