package com.github.postyizhan.betterquesting.network.fragment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestingFragmentCodecTest {
    private static final FragmentAssemblyLimits LIMITS = new FragmentAssemblyLimits(
        4, 12, 4, 3, 512L, 6, 10L);

    @Test
    void roundTripsEveryWireFieldAndOwnsPayload() {
        QuestingFragment original = new QuestingFragment(
            Long.MIN_VALUE, 6, 1, 2, new byte[] {3, 4, 5});
        QuestingFragmentCodec codec = new QuestingFragmentCodec(LIMITS);

        byte[] encoded = codec.encode(original);
        Optional<QuestingFragment> decoded = codec.decode(encoded);

        assertEquals(Optional.of(original), decoded);
        byte[] exposed = decoded.orElseThrow().bytes();
        exposed[0] = 99;
        assertArrayEquals(new byte[] {3, 4, 5}, decoded.orElseThrow().bytes());
        assertEquals(QuestingFragmentCodec.HEADER_BYTES + 3, encoded.length);
        assertEquals(codec.maxEncodedBytes(),
            QuestingFragmentCodec.HEADER_BYTES + LIMITS.maxFragmentBytes());
        assertEquals(codec.maxEncodedBytes(), codec.encode(
            new QuestingFragment(1L, 4, 0, 1, new byte[] {1, 2, 3, 4})).length);
    }

    @Test
    void rejectsNullTruncatedTrailingAndBadHeaderBytes() {
        QuestingFragmentCodec codec = new QuestingFragmentCodec(LIMITS);
        byte[] encoded = codec.encode(new QuestingFragment(7L, 3, 0, 1, new byte[] {1, 2, 3}));

        assertTrue(codec.decode(null).isEmpty());
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(codec.decode(Arrays.copyOf(encoded, length)).isEmpty(), "length=" + length);
        }
        assertTrue(codec.decode(Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());

        byte[] badMagic = encoded.clone();
        badMagic[0] ^= 1;
        assertTrue(codec.decode(badMagic).isEmpty());

        byte[] badVersion = encoded.clone();
        badVersion[Integer.BYTES] = 2;
        assertTrue(codec.decode(badVersion).isEmpty());
    }

    @Test
    void rejectsNegativeOversizedAndImpossibleMetadata() {
        QuestingFragmentCodec codec = new QuestingFragmentCodec(LIMITS);
        byte[] valid = codec.encode(new QuestingFragment(8L, 4, 0, 1, new byte[] {1, 2, 3, 4}));

        assertTrue(codec.decode(withInt(valid, 13, -1)).isEmpty()); // total length
        assertTrue(codec.decode(withInt(valid, 13, 0)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 17, -1)).isEmpty()); // fragment index
        assertTrue(codec.decode(withInt(valid, 17, 1)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 21, -1)).isEmpty()); // fragment count
        assertTrue(codec.decode(withInt(valid, 21, 0)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 21, LIMITS.maxFragmentsPerTransfer() + 1)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 25, -1)).isEmpty()); // payload length
        assertTrue(codec.decode(withInt(valid, 25, 0)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 25, LIMITS.maxFragmentBytes() + 1)).isEmpty());
        assertTrue(codec.decode(withInt(valid, 13, LIMITS.maxTransferBytes() + 1)).isEmpty());

        byte[] impossibleCount = codec.encode(new QuestingFragment(9L, 4, 0, 1, new byte[] {1, 2, 3, 4}));
        assertTrue(codec.decode(withInt(impossibleCount, 21, 2)).isEmpty());

        byte[] impossibleLayout = codec.encode(new QuestingFragment(10L, 4, 0, 1, new byte[] {1, 2, 3, 4}));
        assertTrue(codec.decode(withInt(impossibleLayout, 13, 5)).isEmpty());
    }

    @Test
    void rejectsInconsistentPayloadLengthAndEncodedSize() {
        QuestingFragmentCodec codec = new QuestingFragmentCodec(LIMITS);
        byte[] encoded = codec.encode(new QuestingFragment(11L, 6, 0, 2, new byte[] {1, 2, 3}));

        assertTrue(codec.decode(withInt(encoded, 25, 2)).isEmpty());
        assertTrue(codec.decode(withInt(encoded, 25, 4)).isEmpty());
        assertTrue(codec.decode(new byte[codec.maxEncodedBytes() + 1]).isEmpty());
    }

    @Test
    void encodeRejectsFragmentsOutsideTheConfiguredWirePolicy() {
        QuestingFragmentCodec codec = new QuestingFragmentCodec(LIMITS);

        assertThrows(NullPointerException.class, () -> new QuestingFragmentCodec(null));
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragmentCodec(
            new FragmentAssemblyLimits(Integer.MAX_VALUE, 1, 1, 1, 1L, 1, 1L)));
        assertThrows(NullPointerException.class, () -> codec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
            new QuestingFragment(1L, 13, 0, 4, new byte[] {1, 2, 3, 4})));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
            new QuestingFragment(1L, 5, 0, 5, new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
            new QuestingFragment(1L, 5, 0, 1, new byte[] {1, 2, 3, 4})));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
            new QuestingFragment(1L, 5, 0, 2, new byte[] {1, 2, 3, 4, 5})));
    }

    private static byte[] withInt(byte[] source, int offset, int value) {
        byte[] copy = source.clone();
        ByteBuffer.wrap(copy).putInt(offset, value);
        return copy;
    }
}
