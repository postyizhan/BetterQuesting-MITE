package com.github.postyizhan.betterquesting.core.storage.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Gson 2.2.2-only document IO.
 *
 * <p>The runtime classpath carries Gson 2.2.2, so {@code JsonParser.parseReader},
 * {@code JsonObject.keySet/size} and the {@code JsonArray} convenience overloads used by upstream
 * 1.7.10 are unavailable. Only {@code new JsonParser().parse(Reader)} and {@code JsonWriter} appear
 * here; see docs/platform-probes.md.
 *
 * <p>Documents are written through {@code JsonWriter} with a tab indent, matching upstream
 * {@code JsonHelper.WriteToFile2}'s {@code json.setIndent("\t")} (JsonHelper.java:290) which is the
 * path that produces {@code QuestDatabase.json} (SaveLoadHandler.java:314).
 */
public final class JsonDocuments {
    /** Upstream JsonHelper.java:290. */
    private static final String INDENT = "\t";

    private JsonDocuments() {
    }

    /**
     * Parses a UTF-8 JSON object.
     *
     * <p>Deliberate deviation: upstream {@code GSON.fromJson(reader, JsonObject.class)} returns null
     * for an empty document and lets the caller carry on with defaults, which would let a truncated
     * database be silently replaced by an empty one on the next save. This rejects anything that is
     * not a JSON object so the caller can quarantine the file instead.
     *
     * @throws MalformedJsonDocumentException when the bytes are not a syntactically valid JSON
     *     object
     */
    public static JsonObject parseObject(InputStream input) throws IOException {
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(reader);
        } catch (JsonIOException ioFailure) {
            // Gson wraps the underlying IOException; unwrap it so IO problems are not reported as
            // malformed content.
            Throwable cause = ioFailure.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw ioFailure;
        } catch (JsonSyntaxException syntaxFailure) {
            throw new MalformedJsonDocumentException("Malformed JSON document", syntaxFailure);
        }

        if (parsed == null || !parsed.isJsonObject()) {
            throw new MalformedJsonDocumentException(
                "Expected a JSON object at the document root but found " + describe(parsed));
        }
        return parsed.getAsJsonObject();
    }

    /** Parses a JSON object from a string; used by tests and in-memory conversions. */
    public static JsonObject parseObject(String document) throws IOException {
        return parseObject(new java.io.ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    /** Rejects excessive structure and ambiguous lenient tokens before Gson can recurse. */
    public static JsonObject parseBoundedObject(String document, int maxDepth) throws IOException {
        int depth = 0;
        LexicalState state = LexicalState.NORMAL;
        boolean escaped = false;
        char stringDelimiter = 0;
        for (int index = 0; index < document.length(); index++) {
            char value = document.charAt(index);
            switch (state) {
                case STRING -> {
                    if (escaped) escaped = false;
                    else if (value == '\\') escaped = true;
                    else if (value == stringDelimiter) state = LexicalState.NORMAL;
                }
                case LINE_COMMENT -> {
                    if (value == '\n' || value == '\r') state = LexicalState.NORMAL;
                }
                case BLOCK_COMMENT -> {
                    if (value == '*' && index + 1 < document.length()
                        && document.charAt(index + 1) == '/') {
                        state = LexicalState.NORMAL;
                        index++;
                    }
                }
                case UNQUOTED -> {
                    if (value == '"' || value == '\'') {
                        throw malformed("quote inside lenient unquoted JSON token");
                    }
                    if (isUnquotedDelimiter(value)) {
                        state = LexicalState.NORMAL;
                        index--;
                    }
                }
                case NORMAL -> {
                    if (value == '"' || value == '\'') {
                        state = LexicalState.STRING;
                        stringDelimiter = value;
                    } else if (value == '#') {
                        state = LexicalState.LINE_COMMENT;
                    } else if (value == '/' && index + 1 < document.length()) {
                        char next = document.charAt(index + 1);
                        if (next == '/' || next == '*') {
                            state = next == '/' ? LexicalState.LINE_COMMENT
                                : LexicalState.BLOCK_COMMENT;
                            index++;
                        }
                    } else if (value == '{' || value == '[') {
                        if (++depth > maxDepth) {
                            throw malformed("JSON structural depth exceeds " + maxDepth);
                        }
                    } else if ((value == '}' || value == ']') && depth > 0) {
                        depth--;
                    } else if (!isGsonWhitespace(value)
                        && value != ',' && value != ':' && value != ';' && value != '=') {
                        if (value == '\\') {
                            throw malformed("backslash in lenient unquoted JSON token");
                        }
                        state = LexicalState.UNQUOTED;
                    }
                }
            }
        }
        if (state == LexicalState.STRING) throw malformed("unterminated JSON string");
        if (state == LexicalState.BLOCK_COMMENT) {
            throw malformed("unterminated JSON block comment");
        }
        return parseObject(document);
    }

    /**
     * Opens a tab-indented UTF-8 {@code JsonWriter} over {@code output}. The caller must flush the
     * returned writer; the returned writer owns a buffer over {@code output} but not
     * {@code output} itself, so closing it is the caller's decision.
     */
    public static JsonWriter writer(OutputStream output) {
        Writer characters = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        JsonWriter json = new JsonWriter(characters);
        json.setIndent(INDENT);
        return json;
    }

    private static String describe(JsonElement element) {
        if (element == null) {
            return "nothing";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        return "a primitive";
    }

    private static boolean isUnquotedDelimiter(char value) {
        return isGsonWhitespace(value) || value == '\f' || value == '#' || value == '/'
            || value == ',' || value == ':' || value == ';' || value == '='
            || value == '[' || value == ']' || value == '{' || value == '}'
            || value == '\\';
    }

    private static boolean isGsonWhitespace(char value) {
        return value == '\t' || value == '\n' || value == '\r' || value == ' ';
    }

    private static MalformedJsonDocumentException malformed(String message) {
        return new MalformedJsonDocumentException(message);
    }

    private enum LexicalState { NORMAL, UNQUOTED, STRING, LINE_COMMENT, BLOCK_COMMENT }
}
