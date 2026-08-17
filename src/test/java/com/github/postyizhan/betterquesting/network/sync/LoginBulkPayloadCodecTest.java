package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginBulkPayloadCodecTest {
    private static final UUID PLAYER_ID =
        UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final int ID_OFFSET = Integer.BYTES + 2;
    private static final int PAYLOAD_VERSION_OFFSET = ID_OFFSET + 24;
    private static final int BODY_LENGTH_OFFSET = PAYLOAD_VERSION_OFFSET + 1;
    private static final int BODY_OFFSET = BODY_LENGTH_OFFSET + Integer.BYTES;

    @Test
    void stableLifeEnvelopeHasAnExactCanonicalVector() {
        LoginBulkPayload payload = LoginBulkPayload.life(new LoginLifeSnapshot(-17));

        byte[] encoded = LoginBulkPayloadCodec.encode(payload);

        assertEquals("betterquesting:life_sync", payload.id());
        assertEquals(1, payload.version());
        assertEquals(
            "42514c5001186265747465727175657374696e673a6c6966655f73796e630100000009"
                + "42514c5301ffffffef",
            HexFormat.of().formatHex(encoded));
        assertEquals(payload, LoginBulkPayloadCodec.decode(encoded).orElseThrow());
    }

    @Test
    void roundTripsTheClosedNameVariantThroughTheCanonicalTypedEnvelope() {
        LoginBulkPayload payload = LoginBulkPayload.name(
            new LoginNameSnapshot(PLAYER_ID, "Alice"));

        byte[] encoded = LoginBulkPayloadCodec.encode(payload);
        LoginBulkPayload decoded = LoginBulkPayloadCodec.decode(encoded).orElseThrow();

        assertEquals("betterquesting:login_name", payload.id());
        assertEquals(1, payload.version());
        assertEquals(payload, decoded);
        assertEquals(new LoginNameSnapshot(PLAYER_ID, "Alice"), decoded.name());
        assertTrue(encoded.length <= LoginBulkPayloadCodec.MAX_ENCODED_BYTES);
    }

    @Test
    void nameEnvelopeRejectsUnknownVersionMalformedNbtAndEnvelopeTrailingData() {
        byte[] encoded = LoginBulkPayloadCodec.encode(LoginBulkPayload.name(
            new LoginNameSnapshot(PLAYER_ID, "Alice")));
        int idLength = Byte.toUnsignedInt(encoded[Integer.BYTES + 1]);
        int payloadVersionOffset = ID_OFFSET + idLength;
        int bodyLengthOffset = payloadVersionOffset + 1;
        int bodyOffset = bodyLengthOffset + Integer.BYTES;

        byte[] wrongVersion = encoded.clone();
        wrongVersion[payloadVersionOffset]++;
        assertTrue(LoginBulkPayloadCodec.decode(wrongVersion).isEmpty());

        byte[] malformedNbt = encoded.clone();
        malformedNbt[bodyOffset] = 0;
        assertTrue(LoginBulkPayloadCodec.decode(malformedNbt).isEmpty());

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        ByteBuffer.wrap(trailing).putInt(bodyLengthOffset, encoded.length - bodyOffset + 1);
        assertTrue(LoginBulkPayloadCodec.decode(trailing).isEmpty());
    }

    @Test
    void rejectsUnknownIdsVersionsLengthsAndMalformedBodies() {
        byte[] encoded = LoginBulkPayloadCodec.encode(
            LoginBulkPayload.life(new LoginLifeSnapshot(9)));

        assertTrue(LoginBulkPayloadCodec.decode(null).isEmpty());
        byte[] wrongMagic = encoded.clone();
        wrongMagic[0] ^= 1;
        assertTrue(LoginBulkPayloadCodec.decode(wrongMagic).isEmpty());
        byte[] wrongEnvelopeVersion = encoded.clone();
        wrongEnvelopeVersion[Integer.BYTES]++;
        assertTrue(LoginBulkPayloadCodec.decode(wrongEnvelopeVersion).isEmpty());
        byte[] unknownId = encoded.clone();
        unknownId[ID_OFFSET] = 'x';
        assertTrue(LoginBulkPayloadCodec.decode(unknownId).isEmpty());
        byte[] malformedUtf8Id = encoded.clone();
        malformedUtf8Id[ID_OFFSET] = (byte) 0xc0;
        assertTrue(LoginBulkPayloadCodec.decode(malformedUtf8Id).isEmpty());
        byte[] oversizedId = encoded.clone();
        oversizedId[Integer.BYTES + 1] = 65;
        assertTrue(LoginBulkPayloadCodec.decode(oversizedId).isEmpty());
        byte[] unknownPayloadVersion = encoded.clone();
        unknownPayloadVersion[PAYLOAD_VERSION_OFFSET]++;
        assertTrue(LoginBulkPayloadCodec.decode(unknownPayloadVersion).isEmpty());

        assertRejectedLength(encoded, -1);
        assertRejectedLength(encoded, Integer.MAX_VALUE);
        assertRejectedLength(encoded, LoginLifeSnapshotCodec.MAX_ENCODED_BYTES + 1);
        assertRejectedLength(encoded, LoginLifeSnapshotCodec.MAX_ENCODED_BYTES - 1);

        byte[] malformedBody = encoded.clone();
        malformedBody[BODY_OFFSET] ^= 1;
        assertTrue(LoginBulkPayloadCodec.decode(malformedBody).isEmpty());
        byte[] malformedBodyVersion = encoded.clone();
        malformedBodyVersion[BODY_OFFSET + Integer.BYTES]++;
        assertTrue(LoginBulkPayloadCodec.decode(malformedBodyVersion).isEmpty());
    }

    @Test
    void rejectsEveryTruncationTrailingDataAndOversizedInput() {
        byte[] encoded = LoginBulkPayloadCodec.encode(
            LoginBulkPayload.life(new LoginLifeSnapshot(Integer.MIN_VALUE)));

        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginBulkPayloadCodec.decode(Arrays.copyOf(encoded, length)).isEmpty());
        }
        assertTrue(LoginBulkPayloadCodec.decode(
            Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());
        assertTrue(LoginBulkPayloadCodec.decode(
            new byte[LoginBulkPayloadCodec.MAX_ENCODED_BYTES + 1]).isEmpty());
        assertThrows(NullPointerException.class, () -> LoginBulkPayloadCodec.encode(null));
    }

    private static void assertRejectedLength(byte[] encoded, int length) {
        byte[] malformed = encoded.clone();
        ByteBuffer.wrap(malformed).putInt(BODY_LENGTH_OFFSET, length);
        assertTrue(LoginBulkPayloadCodec.decode(malformed).isEmpty());
    }
}
