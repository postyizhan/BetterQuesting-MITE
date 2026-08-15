package com.github.postyizhan.betterquesting.network.handshake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandshakeHelloCodecTest {
    @Test
    void roundTripIsDeterministicAndCarriesAllCapabilityBits() {
        HandshakeHello hello = new HandshakeHello(
            new UUID(0x0102030405060708L, 0x1112131415161718L),
            new HandshakeCapabilities(17, 23, 0x1020304050607L, 0x5L));

        byte[] encoded = HandshakeHelloCodec.encode(hello);

        assertEquals(HandshakeHelloCodec.ENCODED_BYTES, encoded.length);
        assertEquals(hello, HandshakeHelloCodec.decode(encoded).orElseThrow());
        assertArrayEquals(encoded, HandshakeHelloCodec.encode(
            HandshakeHelloCodec.decode(encoded).orElseThrow()));
    }

    @Test
    void malformedTruncatedTrailingAndOversizedHelloIsRejected() {
        byte[] encoded = HandshakeHelloCodec.encode(new HandshakeHello(
            UUID.randomUUID(), new HandshakeCapabilities(1, 1, 0L, 0L)));
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(HandshakeHelloCodec.decode(Arrays.copyOf(encoded, length)).isEmpty());
        }
        assertTrue(HandshakeHelloCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());
        assertTrue(HandshakeHelloCodec.decode(new byte[HandshakeHelloCodec.MAX_ENCODED_BYTES + 1]).isEmpty());
        assertTrue(HandshakeHelloCodec.decode(null).isEmpty());
    }

    @Test
    void invalidVersionAndCapabilityValuesAreRejected() {
        HandshakeHello hello = new HandshakeHello(
            UUID.randomUUID(), new HandshakeCapabilities(1, 1, 1L, 1L));
        byte[] encoded = HandshakeHelloCodec.encode(hello);

        byte[] version = encoded.clone();
        version[4] = 2;
        assertTrue(HandshakeHelloCodec.decode(version).isEmpty());

        byte[] negativeSupported = encoded.clone();
        // magic (4), version (1), token (16), protocol (4), format (4)
        negativeSupported[29] = (byte) 0x80;
        assertTrue(HandshakeHelloCodec.decode(negativeSupported).isEmpty());
    }
}
