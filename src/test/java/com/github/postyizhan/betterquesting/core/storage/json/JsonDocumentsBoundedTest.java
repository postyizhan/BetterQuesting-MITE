package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class JsonDocumentsBoundedTest {
    @Test
    void unicodeWhitespaceCannotHideDepthAfterAnUnquotedName() throws IOException {
        JsonObject gsonAccepted = JsonDocuments.parseObject(ambiguousUnquotedName(129));
        assertTrue(gsonAccepted.get("deep\u2003\"").isJsonArray());

        for (int depth : new int[] {129, 20_000}) {
            MalformedJsonDocumentException failure = assertThrows(
                MalformedJsonDocumentException.class,
                () -> JsonDocuments.parseBoundedObject(ambiguousUnquotedName(depth), 128));

            assertTrue(failure.getMessage().contains("unquoted"), failure.getMessage());
        }
    }

    @Test
    void unicodeRemainsValidInsideQuotedNamesAndValues() throws IOException {
        JsonObject parsed = JsonDocuments.parseBoundedObject(
            "{\"groups:9\":{},\"unicode\u2003key\":\"snowman \u2603 and space \u2003\"}", 128);

        assertEquals("snowman \u2603 and space \u2003", parsed.get("unicode\u2003key").getAsString());
    }

    private static String ambiguousUnquotedName(int depth) {
        return "{\"groups:9\":{},deep\u2003\":" + "[".repeat(depth) + "0"
            + "]".repeat(depth) + ",end\":0}";
    }
}
