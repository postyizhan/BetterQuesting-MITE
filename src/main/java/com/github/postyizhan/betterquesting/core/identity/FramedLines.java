package com.github.postyizhan.betterquesting.core.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits raw file bytes into LF-framed lines and exposes any unterminated tail.
 *
 * <p>{@code WorldStorage.readLines} discards a trailing fragment silently, which would hide a
 * half-written record. These helpers keep the fragment visible so callers can report it. Splitting
 * on the LF byte before decoding is safe because 0x0A cannot occur inside a multi-byte UTF-8
 * sequence.
 */
final class FramedLines {
    private FramedLines() {
    }

    static List<String> completeLines(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                lines.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
                start = index + 1;
            }
        }
        return List.copyOf(lines);
    }

    static boolean hasUnterminatedTail(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return bytes.length > 0 && bytes[bytes.length - 1] != '\n';
    }

    static String unterminatedTail(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int start = bytes.length;
        while (start > 0 && bytes[start - 1] != '\n') {
            start--;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }
}
