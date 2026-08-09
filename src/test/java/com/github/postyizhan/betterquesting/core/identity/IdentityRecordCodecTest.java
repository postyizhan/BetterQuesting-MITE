package com.github.postyizhan.betterquesting.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityRecordCodecTest {
    private static final String MAGIC = "BQTEST1";

    @Test
    void roundTripsPayloadFields() throws Exception {
        String line = IdentityRecordCodec.encode(MAGIC, List.of("alpha", "beta"));

        assertEquals(List.of("alpha", "beta"), IdentityRecordCodec.decode(MAGIC, 2, line));
    }

    @Test
    void roundTripsSeparatorEscapeAndTerminatorCharactersInFreeFormText() throws Exception {
        String decision = "admin said a|b\\c\nnext\rline";
        String line = IdentityRecordCodec.encode(MAGIC, List.of(decision));

        assertEquals(-1, line.indexOf('\n'));
        assertEquals(-1, line.indexOf('\r'));
        assertEquals(List.of(decision), IdentityRecordCodec.decode(MAGIC, 1, line));
    }

    @Test
    void rejectsWrongFieldCount() {
        String line = IdentityRecordCodec.encode(MAGIC, List.of("only"));

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 2, line)).getMessage().contains("expected 4 fields"));
    }

    @Test
    void rejectsTamperedPayloadWithStaleChecksum() {
        String line = IdentityRecordCodec.encode(MAGIC, List.of("alice"));
        String tampered = line.replace("alice", "carol");

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, tampered)).getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsTamperedChecksumField() {
        String line = IdentityRecordCodec.encode(MAGIC, List.of("alice"));
        String forged = line.substring(0, line.lastIndexOf(IdentityRecordCodec.SEPARATOR) + 1) + "00000000";

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, forged)).getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsForeignMagic() {
        String line = IdentityRecordCodec.encode("BQOTHER1", List.of("alice"));

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, line)).getMessage().contains("expected magic"));
    }

    @Test
    void rejectsUnknownEscapeSequence() {
        // "\\x" is not a defined escape; a hand-edited line must not decode even with a valid checksum.
        String line = withValidChecksum(MAGIC + "|al\\xice");

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, line)).getMessage().contains("unknown escape sequence"));
    }

    @Test
    void anEscapedSeparatorDoesNotSplitAField() throws Exception {
        String line = withValidChecksum(MAGIC + "|alice\\|bob");

        // One payload field, not two: the escaped separator stays inside the field.
        assertEquals(List.of("alice|bob"), IdentityRecordCodec.decode(MAGIC, 1, line));
        assertThrows(IdentityRecordFormatException.class, () -> IdentityRecordCodec.decode(MAGIC, 2, line));
    }

    @Test
    void rejectsATrailingEscapeThatSwallowsTheChecksumSeparator() {
        // A field ending in "\" escapes the separator that should precede the checksum, so the line
        // loses a field boundary. The count check catches it before the unescape step; the
        // "dangling escape" guard in unescape is therefore defence in depth, not the primary check.
        String line = withValidChecksum(MAGIC + "|alice\\");

        assertTrue(assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, line)).getMessage().contains("expected 3 fields"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "|", "BQTEST1", "BQTEST1|alice"})
    void rejectsTruncatedAndEmptyLines(String line) {
        assertThrows(IdentityRecordFormatException.class, () -> IdentityRecordCodec.decode(MAGIC, 1, line));
    }

    @Test
    void rejectsChecksumFieldThatIsNotEightLowercaseHexDigits() {
        assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, MAGIC + "|alice|ABCDEF12"));
        assertThrows(IdentityRecordFormatException.class,
            () -> IdentityRecordCodec.decode(MAGIC, 1, MAGIC + "|alice|1234567"));
    }

    /** Recomputes the trailing checksum so a test can target a non-checksum failure in isolation. */
    private static String withValidChecksum(String body) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return body + IdentityRecordCodec.SEPARATOR + String.format("%08x", crc.getValue());
    }
}
