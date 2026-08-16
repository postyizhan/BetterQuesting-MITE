package com.github.postyizhan.betterquesting.network.fragment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedFragmenterTest {
    private static final FragmentAssemblyLimits BASE_LIMITS = limits(4, 12, 4);

    @Test
    void singleFragmentRoundTripsAtExactLimit() {
        byte[] payload = bytes(1, 2, 3, 4);
        FragmentAssemblyLimits limits = limits(4, 4, 1);
        List<QuestingFragment> fragments = new BoundedFragmenter(limits).split(37L, payload);

        assertEquals(1, fragments.size());
        QuestingFragment fragment = fragments.get(0);
        assertEquals(37L, fragment.transferId());
        assertEquals(4, fragment.totalLength());
        assertEquals(0, fragment.fragmentIndex());
        assertEquals(1, fragment.fragmentCount());
        assertArrayEquals(payload, fragment.bytes());

        BoundedFragmentAssembler.Result result =
            new BoundedFragmentAssembler(limits).accept(fragment, 0L);
        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED, result.outcome());
        assertArrayEquals(payload, result.payload().orElseThrow());
    }

    @Test
    void multiFragmentMetadataIsCanonical() {
        byte[] payload = bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<QuestingFragment> fragments =
            new BoundedFragmenter(BASE_LIMITS).split(Long.MIN_VALUE, payload);

        assertEquals(3, fragments.size());
        for (int index = 0; index < fragments.size(); index++) {
            QuestingFragment fragment = fragments.get(index);
            assertEquals(Long.MIN_VALUE, fragment.transferId());
            assertEquals(payload.length, fragment.totalLength());
            assertEquals(index, fragment.fragmentIndex());
            assertEquals(fragments.size(), fragment.fragmentCount());
            assertTrue(fragment.bytes().length >= 1);
            assertTrue(fragment.bytes().length <= BASE_LIMITS.maxFragmentBytes());
        }
        assertArrayEquals(bytes(0, 1, 2, 3), fragments.get(0).bytes());
        assertArrayEquals(bytes(4, 5, 6, 7), fragments.get(1).bytes());
        assertArrayEquals(bytes(8, 9), fragments.get(2).bytes());
    }

    @Test
    void reverseOrderReassemblesOriginalBytes() {
        byte[] payload = bytes(9, 8, 7, 6, 5, 4, 3, 2, 1, 0, -1);
        List<QuestingFragment> fragments = new BoundedFragmenter(BASE_LIMITS).split(-91L, payload);
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);

        BoundedFragmentAssembler.Result result = null;
        long nowNanos = 0L;
        for (int index = fragments.size() - 1; index >= 0; index--) {
            result = assembler.accept(fragments.get(index), nowNanos++);
        }

        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED, result.outcome());
        assertArrayEquals(payload, result.payload().orElseThrow());
    }

    @Test
    void rejectsNullEmptyAndOversizedPayloads() {
        assertThrows(NullPointerException.class, () -> new BoundedFragmenter(null));

        BoundedFragmenter fragmenter = new BoundedFragmenter(BASE_LIMITS);
        assertThrows(NullPointerException.class, () -> fragmenter.split(1L, null));
        assertThrows(IllegalArgumentException.class, () -> fragmenter.split(1L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> fragmenter.split(1L, new byte[13]));
    }

    @Test
    void rejectsRequiredCountAboveLimitWithoutPartialResult() {
        BoundedFragmenter fragmenter = new BoundedFragmenter(limits(2, 10, 2));

        assertThrows(IllegalArgumentException.class, () -> fragmenter.split(12L, new byte[5]));

        List<QuestingFragment> valid = fragmenter.split(13L, new byte[4]);
        assertEquals(2, valid.size());
        assertEquals(0, valid.get(0).fragmentIndex());
        assertEquals(1, valid.get(1).fragmentIndex());
    }

    @Test
    void copiesInputAndReturnsUnmodifiableList() {
        byte[] payload = bytes(1, 2, 3, 4, 5);
        List<QuestingFragment> fragments = new BoundedFragmenter(BASE_LIMITS).split(7L, payload);

        payload[0] = 99;
        payload[4] = 99;
        assertArrayEquals(bytes(1, 2, 3, 4), fragments.get(0).bytes());
        assertArrayEquals(bytes(5), fragments.get(1).bytes());

        byte[] exposed = fragments.get(0).bytes();
        exposed[0] = 88;
        assertArrayEquals(bytes(1, 2, 3, 4), fragments.get(0).bytes());
        assertThrows(UnsupportedOperationException.class, () -> fragments.add(fragments.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> fragments.remove(0));
    }

    private static FragmentAssemblyLimits limits(
        int maxFragmentBytes,
        int maxTransferBytes,
        int maxFragmentsPerTransfer
    ) {
        return new FragmentAssemblyLimits(
            maxFragmentBytes,
            maxTransferBytes,
            maxFragmentsPerTransfer,
            3,
            512L,
            6,
            10L
        );
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
