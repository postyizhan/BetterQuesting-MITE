package com.github.postyizhan.betterquesting.core.storage.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Access to the corruption fixture corpus under {@code src/test/resources/fixtures}.
 *
 * <p>Two directories, two contracts:
 * <ul>
 *   <li>{@code fixtures/malformed} — every file must be rejected by
 *       {@link JsonDocuments#parseObject(InputStream)} and quarantined by
 *       {@link JsonDocumentStore#load}.</li>
 *   <li>{@code fixtures/lenient} — documents that look damaged but that Gson 2.2.2 accepts, so the
 *       corpus records what quarantine does <em>not</em> catch.</li>
 * </ul>
 *
 * <p>Fixtures are byte-exact: several carry NUL padding, a UTF-8 BOM or zero length, which is why
 * {@code .gitattributes} marks the directory {@code -text}. Oversized inputs are generated here
 * instead of being committed, so the repository stays free of multi-megabyte blobs.
 */
final class MalformedJsonFixtures {
    static final String MALFORMED = "malformed";
    static final String LENIENT = "lenient";

    /**
     * Nesting depth that reliably exhausts the parser's stack. Measured against Gson 2.2.2 on this
     * JVM, the recursive {@code TypeAdapters.JSON_ELEMENT} reader overflows at depth ~4100 with the
     * default 1 MiB thread stack and at ~1980 with a 512 KiB stack, so 20000 keeps a wide margin
     * without depending on an exact frame size.
     */
    static final int OVERFLOWING_DEPTH = 20000;

    /** Nesting depth well inside the parser's limit, used to show where the ceiling is not. */
    static final int ACCEPTED_DEPTH = 200;

    /** Element count for the oversized array case; ~1.6 MB of JSON, parsed in well under a second. */
    static final int HUGE_ARRAY_ELEMENTS = 250_000;

    private MalformedJsonFixtures() {
    }

    static byte[] bytes(String directory, String name) {
        String resource = "/fixtures/" + directory + "/" + name;
        try (InputStream input = MalformedJsonFixtures.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture resource " + resource);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Every fixture file name in one directory, read off the classpath so a file added without a
     * matching assertion is detected rather than silently ignored.
     */
    static List<String> names(String directory) {
        URL url = MalformedJsonFixtures.class.getResource("/fixtures/" + directory);
        if (url == null) {
            throw new IllegalStateException("Missing fixture directory " + directory);
        }
        try (Stream<Path> entries = Files.list(Path.of(url.toURI()))) {
            return entries
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code {"a:10":{"a:10":{ ... "a:3":1 ... }}}} nested {@code depth} levels deep. */
    static String nestedObjects(int depth) {
        StringBuilder document = new StringBuilder(depth * 8 + 8);
        for (int level = 0; level < depth; level++) {
            document.append("{\"a:10\":");
        }
        document.append("{\"leaf:3\":1}");
        for (int level = 0; level < depth; level++) {
            document.append('}');
        }
        return document.toString();
    }

    /** A single {@code int[]} member holding {@code elements} ascending values. */
    static String hugeIntArray(int elements) {
        StringBuilder document = new StringBuilder(elements * 7 + 32);
        document.append("{\"questIDs:11\":[");
        for (int index = 0; index < elements; index++) {
            if (index > 0) {
                document.append(',');
            }
            document.append(index);
        }
        return document.append("]}").toString();
    }
}
