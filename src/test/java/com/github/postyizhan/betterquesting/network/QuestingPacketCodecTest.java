package com.github.postyizhan.betterquesting.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestingPacketCodecTest {
    @Test
    void encodesGoldenVector() {
        QuestingPacket packet = new QuestingPacket("bq:test", new byte[]{0x00, 0x7f, (byte) 0xff});

        assertArrayEquals(new byte[]{
            0x01, 0x07, 'b', 'q', ':', 't', 'e', 's', 't', 0x00, 0x7f, (byte) 0xff
        }, QuestingPacketCodec.encode(packet));
    }

    @Test
    void roundTripsOpaquePayload() {
        QuestingPacket original = new QuestingPacket("betterquesting:probe/nonce", new byte[]{3, 1, 4, 1, 5});

        Optional<QuestingPacket> decoded = QuestingPacketCodec.decode(QuestingPacketCodec.encode(original));

        assertEquals(Optional.of(original), decoded);
    }

    @Test
    void defensivelyCopiesPayloadOnConstructionAccessAndDecode() {
        byte[] source = {1, 2, 3};
        QuestingPacket packet = new QuestingPacket("a:b", source);
        source[0] = 9;
        byte[] exposed = packet.payload();
        exposed[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, packet.payload());

        byte[] encoded = QuestingPacketCodec.encode(packet);
        QuestingPacket decoded = QuestingPacketCodec.decode(encoded).orElseThrow();
        encoded[encoded.length - 1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, decoded.payload());
    }

    @Test
    void acceptsEmptyAndMaximumPayloadBoundaries() {
        QuestingPacket empty = new QuestingPacket("a:b", new byte[0]);
        assertEquals(2 + 3, QuestingPacketCodec.encode(empty).length);
        assertEquals(Optional.of(empty), QuestingPacketCodec.decode(QuestingPacketCodec.encode(empty)));

        int maximumPayload = PacketLimits.MAX_ENVELOPE_BYTES - 2 - 3;
        QuestingPacket maximum = new QuestingPacket("a:b", new byte[maximumPayload]);
        byte[] encoded = QuestingPacketCodec.encode(maximum);

        assertEquals(PacketLimits.MAX_ENVELOPE_BYTES, encoded.length);
        assertEquals(Optional.of(maximum), QuestingPacketCodec.decode(encoded));
    }

    @Test
    void rejectsOversizedPackets() {
        int oversizedPayload = PacketLimits.MAX_ENVELOPE_BYTES - 2 - 3 + 1;

        assertThrows(IllegalArgumentException.class, () -> new QuestingPacket("a:b", new byte[oversizedPayload]));
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[PacketLimits.MAX_ENVELOPE_BYTES + 1]));
    }

    @Test
    void rejectsUnsupportedVersionAndTruncation() {
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[]{2, 3, 'a', ':', 'b'}));
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[0]));
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[]{1}));
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[]{1, 3, 'a', ':'}));
    }

    @Test
    void rejectsInvalidEncodedIdLengths() {
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(new byte[]{1, 2, 'a', ':'}));

        byte[] tooLong = new byte[2 + 129];
        tooLong[0] = 1;
        tooLong[1] = (byte) 129;
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(tooLong));
    }

    @Test
    void rejectsInvalidEncodedIdGrammarAndNonAscii() {
        assertRejectedIdBytes("A:b".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertRejectedIdBytes("a:".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertRejectedIdBytes(":ab".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertRejectedIdBytes("a:b:c".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertRejectedIdBytes("a:b\\c".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertRejectedIdBytes(new byte[]{'a', ':', (byte) 0x80});
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "A:b", "a:", ":ab", "a:b:c", "a:b\\c", "a:b c", "a:b?"})
    void rejectsInvalidIdsAtConstruction(String id) {
        assertThrows(IllegalArgumentException.class, () -> new QuestingPacket(id, new byte[0]));
    }

    @Test
    void rejectsNonAsciiIdAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new QuestingPacket("a:\u00e9", new byte[0]));
    }

    @Test
    void malformedCorpusNeverThrows() {
        byte[] oversized = new byte[PacketLimits.MAX_ENVELOPE_BYTES + 1];
        byte[] longId = new byte[131];
        longId[0] = 1;
        longId[1] = (byte) 129;
        List<byte[]> malformed = List.of(
            new byte[0],
            new byte[]{1},
            new byte[]{0, 3, 'a', ':', 'b'},
            new byte[]{2, 3, 'a', ':', 'b'},
            new byte[]{1, 0},
            new byte[]{1, 2, 'a', ':'},
            new byte[]{1, 3, 'a', ':'},
            new byte[]{1, 3, 'A', ':', 'b'},
            new byte[]{1, 3, 'a', ':', (byte) 0xff},
            new byte[]{1, 5, 'a', ':', 'b', ':', 'c'},
            longId,
            oversized
        );

        for (byte[] input : malformed) {
            Optional<QuestingPacket> decoded = assertDoesNotThrow(() -> QuestingPacketCodec.decode(input));
            assertFalse(decoded.isPresent());
        }
        assertTrue(assertDoesNotThrow(() -> QuestingPacketCodec.decode(null)).isEmpty());
    }

    private static void assertRejectedIdBytes(byte[] id) {
        byte[] encoded = new byte[2 + id.length];
        encoded[0] = 1;
        encoded[1] = (byte) id.length;
        System.arraycopy(id, 0, encoded, 2, id.length);
        assertEquals(Optional.empty(), QuestingPacketCodec.decode(encoded));
    }
}
