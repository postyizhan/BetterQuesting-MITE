package com.github.postyizhan.betterquesting.core.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Encodes and decodes one self-validating identity record line.
 *
 * <p>Format: {@code <magic>|<payload 1>|...|<payload n>|<crc32>}. The separator is {@code |}; the
 * checksum is CRC32 over the UTF-8 bytes of every preceding field joined by {@code |}, rendered as
 * exactly 8 lowercase hex digits.
 *
 * <p>Decoding rejects a line unless all of the following hold: the field count is exactly
 * {@code payloadFields + 2}, the magic matches, the checksum field matches the pattern and the
 * recomputed value, no field carries an invalid escape sequence, and re-encoding the decoded
 * payload reproduces the input byte for byte. The last check makes the encoding canonical, so a
 * hand-edited line that is merely equivalent (redundant escapes, for example) is still rejected.
 *
 * <p>This exists because {@code WorldStorage.appendLine}'s LF framing guard only isolates a crash
 * fragment onto its own line; it cannot mark that line as garbage. A fragment of a longer record
 * fails the field count check, and a fragment that happens to have the right field count fails the
 * checksum.
 *
 * <p>CRC32 detects truncation and accidental corruption. It is not a MAC: anyone able to write the
 * file can recompute a valid checksum. Tamper-evidence against a privileged local editor is out of
 * scope for this boundary.
 *
 * <p>No upstream counterpart exists. Upstream BetterQuesting stores no identity mapping and no
 * audit log because 1.7.10 supplies verifiable Mojang UUIDs; see docs/handoff.md section 4.1.
 */
final class IdentityRecordCodec {
    static final char SEPARATOR = '|';
    private static final char ESCAPE = '\\';
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{8}");

    private IdentityRecordCodec() {
    }

    static String encode(String magic, List<String> payload) {
        Objects.requireNonNull(magic, "magic");
        Objects.requireNonNull(payload, "payload");
        StringBuilder prefix = new StringBuilder(escape(magic));
        for (String field : payload) {
            prefix.append(SEPARATOR).append(escape(Objects.requireNonNull(field, "payload field")));
        }
        String body = prefix.toString();
        return body + SEPARATOR + checksum(body);
    }

    static List<String> decode(String magic, int payloadFields, String line) throws IdentityRecordFormatException {
        Objects.requireNonNull(magic, "magic");
        Objects.requireNonNull(line, "line");
        if (payloadFields < 0) {
            throw new IllegalArgumentException("payloadFields must not be negative");
        }

        List<String> rawFields = splitOnUnescapedSeparator(line);
        int expectedFields = payloadFields + 2;
        if (rawFields.size() != expectedFields) {
            throw new IdentityRecordFormatException("expected " + expectedFields + " fields but found "
                + rawFields.size());
        }

        String rawChecksum = rawFields.get(rawFields.size() - 1);
        if (!CHECKSUM.matcher(rawChecksum).matches()) {
            throw new IdentityRecordFormatException("checksum field is not 8 lowercase hex digits");
        }
        String body = line.substring(0, line.length() - rawChecksum.length() - 1);
        String expectedChecksum = checksum(body);
        if (!expectedChecksum.equals(rawChecksum)) {
            throw new IdentityRecordFormatException("checksum mismatch: expected " + expectedChecksum
                + " but found " + rawChecksum);
        }

        List<String> decoded = new ArrayList<>(rawFields.size() - 1);
        for (int index = 0; index < rawFields.size() - 1; index++) {
            decoded.add(unescape(rawFields.get(index)));
        }
        if (!magic.equals(decoded.get(0))) {
            throw new IdentityRecordFormatException("expected magic " + magic + " but found " + decoded.get(0));
        }

        List<String> payload = List.copyOf(decoded.subList(1, decoded.size()));
        String canonical = encode(magic, payload);
        if (!canonical.equals(line)) {
            throw new IdentityRecordFormatException("record encoding is not canonical");
        }
        return payload;
    }

    /**
     * Escapes the separator and both line terminators so one record always occupies one line and
     * every field boundary stays unambiguous. Administrator decision text is free-form, so it can
     * legitimately contain any of these characters.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case ESCAPE -> escaped.append(ESCAPE).append(ESCAPE);
                case SEPARATOR -> escaped.append(ESCAPE).append(SEPARATOR);
                case '\n' -> escaped.append(ESCAPE).append('n');
                case '\r' -> escaped.append(ESCAPE).append('r');
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String unescape(String value) throws IdentityRecordFormatException {
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ESCAPE) {
                plain.append(character);
                continue;
            }
            if (index + 1 >= value.length()) {
                throw new IdentityRecordFormatException("record ends with a dangling escape character");
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case ESCAPE -> plain.append(ESCAPE);
                case SEPARATOR -> plain.append(SEPARATOR);
                case 'n' -> plain.append('\n');
                case 'r' -> plain.append('\r');
                default -> throw new IdentityRecordFormatException(
                    "record contains an unknown escape sequence: \\" + escaped);
            }
        }
        return plain.toString();
    }

    private static List<String> splitOnUnescapedSeparator(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaping) {
                current.append(character);
                escaping = false;
            } else if (character == ESCAPE) {
                current.append(character);
                escaping = true;
            } else if (character == SEPARATOR) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }
}
