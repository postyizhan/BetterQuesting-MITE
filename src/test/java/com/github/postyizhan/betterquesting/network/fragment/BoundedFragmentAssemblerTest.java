package com.github.postyizhan.betterquesting.network.fragment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoundedFragmentAssemblerTest {
    private static final FragmentAssemblyLimits BASE_LIMITS = limits(4, 12, 4, 3, 512, 6, 10);

    @Test
    void completesSingleFragmentAndDefensivelyOwnsAllPayloads() {
        byte[] source = {1, 2, 3};
        QuestingFragment fragment = new QuestingFragment(1L, 3, 0, 1, source);
        source[0] = 9;
        byte[] exposedFragment = fragment.bytes();
        exposedFragment[1] = 9;

        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        BoundedFragmentAssembler.Result result = assembler.accept(fragment, 0L);

        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED, result.outcome());
        byte[] completed = result.payload().orElseThrow();
        completed[2] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, fragment.bytes());
        assertArrayEquals(new byte[] {1, 2, 3}, result.payload().orElseThrow());
        assertEquals(0, assembler.activeTransferCount());
        assertEquals(0L, assembler.reservedBytes());
        assertTrue(assembler.isRetired(1L));
    }

    @Test
    void assemblesOutOfOrderAndKeepsInterleavedTransfersIndependent() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);

        assertAccepted(assembler.accept(fragment(10L, 6, 2, 3, 5, 6), 0L));
        assertAccepted(assembler.accept(fragment(20L, 4, 1, 2, 9, 10), 1L));
        assertAccepted(assembler.accept(fragment(10L, 6, 0, 3, 1, 2), 2L));
        BoundedFragmentAssembler.Result second = assembler.accept(fragment(20L, 4, 0, 2, 7, 8), 3L);
        assertArrayEquals(new byte[] {7, 8, 9, 10}, second.payload().orElseThrow());
        assertEquals(1, assembler.activeTransferCount());

        BoundedFragmentAssembler.Result first = assembler.accept(fragment(10L, 6, 1, 3, 3, 4), 4L);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, first.payload().orElseThrow());
        assertEquals(0, assembler.activeTransferCount());
        assertEquals(0L, assembler.reservedBytes());
    }

    @Test
    void instancesKeepConnectionSessionsIsolated() {
        BoundedFragmentAssembler firstSession = new BoundedFragmentAssembler(BASE_LIMITS);
        BoundedFragmentAssembler secondSession = new BoundedFragmentAssembler(BASE_LIMITS);
        assertAccepted(firstSession.accept(fragment(1L, 4, 0, 2, 1, 2), 0L));

        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED,
            secondSession.accept(fragment(1L, 1, 0, 1, 9), 0L).outcome());
        assertEquals(1, firstSession.activeTransferCount());
        assertEquals(0, firstSession.retiredTransferCount());

        secondSession.close();
        assertArrayEquals(new byte[] {1, 2, 3, 4},
            firstSession.accept(fragment(1L, 4, 1, 2, 3, 4), 1L).payload().orElseThrow());
    }

    @Test
    void validatesFragmentsAndLimitConfigurations() {
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragment(1L, 0, 0, 1, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragment(1L, 1, -1, 1, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragment(1L, 1, 0, 0, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragment(1L, 1, 1, 1, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new QuestingFragment(1L, 1, 0, 1, new byte[0]));
        assertThrows(NullPointerException.class, () -> new QuestingFragment(1L, 1, 0, 1, null));

        assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 1, 1, 1, 1, 1, 0));
    }

    @Test
    void enforcesFragmentTransferAndFragmentCountLimitsAtExactBoundaries() {
        BoundedFragmentAssembler fragmentBytes = new BoundedFragmentAssembler(limits(2, 6, 3, 3, 200, 6, 10));
        assertAccepted(fragmentBytes.accept(fragment(1L, 3, 0, 2, 1, 2), 0L));
        assertRejected(fragmentBytes.accept(fragment(2L, 3, 0, 1, 1, 2, 3), 1L),
            BoundedFragmentAssembler.RejectionReason.FRAGMENT_TOO_LARGE);
        assertEquals(1, fragmentBytes.activeTransferCount());

        BoundedFragmentAssembler transferBytes = new BoundedFragmentAssembler(limits(4, 4, 4, 3, 200, 6, 10));
        assertAccepted(transferBytes.accept(fragment(3L, 4, 0, 2, 1, 2), 0L));
        assertRejected(transferBytes.accept(fragment(4L, 5, 0, 2, 1, 2), 1L),
            BoundedFragmentAssembler.RejectionReason.TRANSFER_TOO_LARGE);
        assertEquals(1, transferBytes.activeTransferCount());

        BoundedFragmentAssembler fragmentCount = new BoundedFragmentAssembler(limits(2, 6, 2, 3, 200, 6, 10));
        assertAccepted(fragmentCount.accept(fragment(5L, 4, 0, 2, 1, 2), 0L));
        assertRejected(fragmentCount.accept(fragment(6L, 3, 0, 3, 1), 1L),
            BoundedFragmentAssembler.RejectionReason.TOO_MANY_FRAGMENTS);
        assertEquals(1, fragmentCount.activeTransferCount());
    }

    @Test
    void rejectsImpossibleLayoutsBeforeRetainingActivePayloadState() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits(2, 20, 20, 4, 40, 8, 10));

        assertRejected(assembler.accept(fragment(1L, 5, 0, 2, 1, 2), 0L),
            BoundedFragmentAssembler.RejectionReason.IMPOSSIBLE_LAYOUT);
        assertRejected(assembler.accept(fragment(2L, 2, 0, 3, 1), 1L),
            BoundedFragmentAssembler.RejectionReason.IMPOSSIBLE_LAYOUT);
        assertRejected(assembler.accept(fragment(3L, 2, 0, 1, 1), 2L),
            BoundedFragmentAssembler.RejectionReason.IMPOSSIBLE_LAYOUT);

        assertEquals(0, assembler.activeTransferCount());
        assertEquals(0L, assembler.reservedBytes());
        assertEquals(3, assembler.retiredTransferCount());
    }

    @Test
    void identicalDuplicateIsIdempotentButConflictRetiresOnlyItsTransfer() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        QuestingFragment first = fragment(1L, 4, 0, 2, 1, 2);
        assertAccepted(assembler.accept(first, 0L));
        assertAccepted(assembler.accept(fragment(2L, 4, 0, 2, 7, 8), 1L));

        BoundedFragmentAssembler.Result duplicate = assembler.accept(first, 2L);
        assertEquals(BoundedFragmentAssembler.Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(2, assembler.activeTransferCount());
        assertEquals(272L, assembler.reservedBytes());

        assertRejected(assembler.accept(fragment(1L, 4, 0, 2, 9, 9), 3L),
            BoundedFragmentAssembler.RejectionReason.CONFLICTING_DUPLICATE);
        assertEquals(1, assembler.activeTransferCount());
        assertEquals(136L, assembler.reservedBytes());
        assertTrue(assembler.isRetired(1L));
        assertFalse(assembler.isRetired(2L));
        assertArrayEquals(new byte[] {7, 8, 9, 10},
            assembler.accept(fragment(2L, 4, 1, 2, 9, 10), 4L).payload().orElseThrow());
    }

    @Test
    void metadataChangeAndImpossibleProgressRetireOnlyTheAffectedTransfer() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        assertAccepted(assembler.accept(fragment(1L, 4, 0, 2, 1, 2), 0L));
        assertAccepted(assembler.accept(fragment(2L, 4, 0, 2, 5, 6), 1L));

        assertRejected(assembler.accept(fragment(1L, 5, 1, 2, 3, 4), 2L),
            BoundedFragmentAssembler.RejectionReason.METADATA_MISMATCH);
        assertEquals(1, assembler.activeTransferCount());
        assertEquals(136L, assembler.reservedBytes());

        assertRejected(assembler.accept(fragment(2L, 4, 1, 2, 7), 3L),
            BoundedFragmentAssembler.RejectionReason.BYTE_SUM_MISMATCH);
        assertEquals(0, assembler.activeTransferCount());
        assertEquals(0L, assembler.reservedBytes());
    }

    @Test
    void enforcesConcurrentAndReservedLimitsWithoutDisturbingUnrelatedTransfers() {
        BoundedFragmentAssembler concurrent = new BoundedFragmentAssembler(limits(3, 6, 3, 2, 512, 5, 10));
        assertAccepted(concurrent.accept(fragment(1L, 3, 0, 2, 1), 0L));
        assertAccepted(concurrent.accept(fragment(2L, 3, 0, 2, 2), 1L));
        assertRejected(concurrent.accept(fragment(3L, 3, 0, 2, 3), 2L),
            BoundedFragmentAssembler.RejectionReason.TOO_MANY_CONCURRENT_TRANSFERS);
        assertEquals(2, concurrent.activeTransferCount());
        assertEquals(268L, concurrent.reservedBytes());
        assertFalse(concurrent.isRetired(3L));
        assertArrayEquals(new byte[] {1, 4, 5},
            concurrent.accept(fragment(1L, 3, 1, 2, 4, 5), 3L).payload().orElseThrow());

        BoundedFragmentAssembler reserved = new BoundedFragmentAssembler(limits(4, 8, 4, 3, 300, 6, 10));
        assertAccepted(reserved.accept(fragment(4L, 4, 0, 2, 1, 2), 0L));
        assertAccepted(reserved.accept(fragment(5L, 4, 0, 2, 3, 4), 1L));
        assertRejected(reserved.accept(fragment(6L, 1, 0, 1, 5), 2L),
            BoundedFragmentAssembler.RejectionReason.RESERVED_BYTES_EXCEEDED);
        assertEquals(2, reserved.activeTransferCount());
        assertEquals(272L, reserved.reservedBytes());
        assertFalse(reserved.isRetired(6L));
        assertArrayEquals(new byte[] {1, 2, 5, 6},
            reserved.accept(fragment(4L, 4, 1, 2, 5, 6), 3L).payload().orElseThrow());
    }

    @Test
    void enforcesTrackedIdLimitAcrossActiveAndRetiredIds() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits(2, 4, 2, 2, 200, 2, 10));
        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED,
            assembler.accept(fragment(1L, 1, 0, 1, 1), 0L).outcome());
        assertAccepted(assembler.accept(fragment(2L, 2, 0, 2, 2), 1L));

        assertRejected(assembler.accept(fragment(3L, 2, 0, 2, 3), 2L),
            BoundedFragmentAssembler.RejectionReason.TRACKED_ID_LIMIT_EXCEEDED);
        assertEquals(1, assembler.activeTransferCount());
        assertEquals(1, assembler.retiredTransferCount());
        assertEquals(2, assembler.trackedTransferIdCount());
        assertFalse(assembler.isRetired(3L));
        assertArrayEquals(new byte[] {2, 4},
            assembler.accept(fragment(2L, 2, 1, 2, 4), 3L).payload().orElseThrow());
    }

    @Test
    void completionFailureTimeoutAndCloseReleaseReservations() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        assertAccepted(assembler.accept(fragment(1L, 4, 0, 2, 1, 2), 0L));
        assertEquals(136L, assembler.reservedBytes());
        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED,
            assembler.accept(fragment(1L, 4, 1, 2, 3, 4), 1L).outcome());
        assertEquals(0L, assembler.reservedBytes());

        assertAccepted(assembler.accept(fragment(2L, 4, 0, 2, 1, 2), 2L));
        assertRejected(assembler.accept(fragment(2L, 4, 0, 2, 3, 4), 3L),
            BoundedFragmentAssembler.RejectionReason.CONFLICTING_DUPLICATE);
        assertEquals(0L, assembler.reservedBytes());
        assertRejected(assembler.accept(fragment(2L, 4, 0, 2, 1, 2), 3L),
            BoundedFragmentAssembler.RejectionReason.RETIRED_TRANSFER_ID);

        assertAccepted(assembler.accept(fragment(3L, 4, 0, 2, 1, 2), 4L));
        assertEquals(1, assembler.expireIdle(14L));
        assertEquals(0L, assembler.reservedBytes());
        assertTrue(assembler.isRetired(3L));
        assertRejected(assembler.accept(fragment(3L, 4, 1, 2, 3, 4), 14L),
            BoundedFragmentAssembler.RejectionReason.RETIRED_TRANSFER_ID);

        assertAccepted(assembler.accept(fragment(4L, 4, 0, 2, 1, 2), 15L));
        assembler.close();
        assertEquals(0, assembler.activeTransferCount());
        assertEquals(0, assembler.retiredTransferCount());
        assertEquals(0L, assembler.reservedBytes());
        assertTrue(assembler.isClosed());
    }

    @Test
    void timesOutAtExactBoundaryAndOnlyDistinctProgressRefreshesTimeout() {
        BoundedFragmentAssembler exact = new BoundedFragmentAssembler(BASE_LIMITS);
        assertAccepted(exact.accept(fragment(1L, 4, 0, 2, 1, 2), 100L));
        assertEquals(0, exact.expireIdle(109L));
        assertEquals(1, exact.expireIdle(110L));

        BoundedFragmentAssembler progress = new BoundedFragmentAssembler(limits(2, 6, 3, 2, 200, 5, 10));
        QuestingFragment first = fragment(2L, 6, 0, 3, 1, 2);
        assertAccepted(progress.accept(first, 0L));
        assertEquals(BoundedFragmentAssembler.Outcome.DUPLICATE, progress.accept(first, 5L).outcome());
        assertAccepted(progress.accept(fragment(2L, 6, 1, 3, 3, 4), 9L));
        assertEquals(BoundedFragmentAssembler.Outcome.DUPLICATE,
            progress.accept(fragment(2L, 6, 1, 3, 3, 4), 15L).outcome());
        assertEquals(0, progress.expireIdle(18L));
        assertEquals(1, progress.expireIdle(19L));
    }

    @Test
    void retiredIdsBlockReplayUntilTheirBoundedRetentionExpires() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits(2, 4, 2, 2, 100, 2, 10));
        QuestingFragment complete = fragment(1L, 1, 0, 1, 7);
        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED, assembler.accept(complete, 0L).outcome());
        assertRejected(assembler.accept(complete, 9L), BoundedFragmentAssembler.RejectionReason.RETIRED_TRANSFER_ID);
        assertEquals(1, assembler.retiredTransferCount());

        assertEquals(BoundedFragmentAssembler.Outcome.COMPLETED, assembler.accept(complete, 10L).outcome());
        assertEquals(1, assembler.retiredTransferCount());
    }

    @Test
    void closeIsPermanentAndRejectsFurtherSessionUse() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        assembler.close();
        assembler.close();

        assertThrows(IllegalStateException.class,
            () -> assembler.accept(fragment(1L, 1, 0, 1, 1), 0L));
        assertThrows(IllegalStateException.class, () -> assembler.expireIdle(0L));
    }

    @Test
    void regressedMonotonicTimeFailsWithoutMutatingState() {
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(BASE_LIMITS);
        assertAccepted(assembler.accept(fragment(1L, 4, 0, 2, 1, 2), 20L));

        assertThrows(IllegalArgumentException.class,
            () -> assembler.accept(fragment(2L, 1, 0, 1, 9), 19L));
        assertEquals(1, assembler.activeTransferCount());
        assertEquals(0, assembler.retiredTransferCount());
        assertEquals(136L, assembler.reservedBytes());
        assertFalse(assembler.isRetired(2L));

        assertThrows(IllegalArgumentException.class, () -> assembler.expireIdle(19L));
        assertEquals(1, assembler.activeTransferCount());
        assertEquals(136L, assembler.reservedBytes());
        assertEquals(0, assembler.expireIdle(29L));
        assertEquals(1, assembler.expireIdle(30L));
    }

    @Test
    void reservesConservativeChargeForTinyFragmentAmplification() {
        FragmentAssemblyLimits limits = limits(1, 8, 8, 2, 500, 8, 10);
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits);

        assertAccepted(assembler.accept(fragment(9L, 8, 0, 8, 1), 0L));
        assertTrue(assembler.reservedBytes() > 8L);
        assertRejected(assembler.accept(fragment(10L, 8, 0, 8, 1), 1L),
            BoundedFragmentAssembler.RejectionReason.RESERVED_BYTES_EXCEEDED);
    }

    @Test
    void reservationBoundaryAndReleaseUseTheSameCharge() {
        FragmentAssemblyLimits limits = limits(2, 6, 3, 2, 188, 4, 10);
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits);

        assertAccepted(assembler.accept(fragment(11L, 6, 0, 3, 1, 2), 0L));
        long charge = assembler.reservedBytes();
        assertEquals(188L, charge);
        BoundedFragmentAssembler second = new BoundedFragmentAssembler(
            limits(2, 6, 3, 2, charge, 4, 10));
        assertAccepted(second.accept(fragment(12L, 6, 0, 3, 1, 2), 0L));
        assertRejected(second.accept(fragment(13L, 6, 0, 3, 1, 2), 1L),
            BoundedFragmentAssembler.RejectionReason.RESERVED_BYTES_EXCEEDED);
        assertEquals(1, assembler.expireIdle(10L));
        assertEquals(1, second.expireIdle(10L));
        assertEquals(0L, second.reservedBytes());
    }

    @Test
    void rejectsHugeReservationWithoutAllocatingHugeArrays() {
        FragmentAssemblyLimits limits = limits(1_000_000_000, 1_000_000_000,
            1_000_000_000, 1, 1_024L, 1, 10);
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits);

        assertRejected(assembler.accept(
            new QuestingFragment(14L, 1_000_000_000, 0, 1_000_000_000, bytes(1)), 0L),
            BoundedFragmentAssembler.RejectionReason.RESERVED_BYTES_EXCEEDED);
        assertEquals(0L, assembler.reservedBytes());
        assertEquals(0, assembler.activeTransferCount());
    }

    @Test
    void expiresAllIdleTransfersAndAllowsSameIdAfterRetentionExpiry() {
        FragmentAssemblyLimits limits = limits(2, 4, 2, 4, 300, 8, 10);
        BoundedFragmentAssembler assembler = new BoundedFragmentAssembler(limits);
        assertAccepted(assembler.accept(fragment(21L, 4, 0, 2, 1, 2), 0L));
        assertAccepted(assembler.accept(fragment(22L, 4, 0, 2, 2, 3), 0L));

        assertEquals(2, assembler.expireIdle(10L));
        assertEquals(0L, assembler.reservedBytes());
        assertRejected(assembler.accept(fragment(21L, 4, 0, 2, 1, 2), 10L),
            BoundedFragmentAssembler.RejectionReason.RETIRED_TRANSFER_ID);
        assertEquals(0, assembler.expireIdle(19L));
        assertEquals(BoundedFragmentAssembler.Outcome.ACCEPTED,
            assembler.accept(fragment(21L, 4, 0, 2, 1, 2), 20L).outcome());
    }

    private static FragmentAssemblyLimits limits(
        int maxFragmentBytes,
        int maxTransferBytes,
        int maxFragmentsPerTransfer,
        int maxConcurrentTransfers,
        long maxReservedBytes,
        int maxTrackedTransferIds,
        long idleTimeoutNanos
    ) {
        return new FragmentAssemblyLimits(
            maxFragmentBytes,
            maxTransferBytes,
            maxFragmentsPerTransfer,
            maxConcurrentTransfers,
            maxReservedBytes,
            maxTrackedTransferIds,
            idleTimeoutNanos
        );
    }

    private static QuestingFragment fragment(
        long transferId,
        int totalLength,
        int fragmentIndex,
        int fragmentCount,
        int... bytes
    ) {
        byte[] payload = new byte[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            payload[index] = (byte) bytes[index];
        }
        return new QuestingFragment(transferId, totalLength, fragmentIndex, fragmentCount, payload);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static void assertAccepted(BoundedFragmentAssembler.Result result) {
        assertEquals(BoundedFragmentAssembler.Outcome.ACCEPTED, result.outcome());
        assertEquals(BoundedFragmentAssembler.RejectionReason.NONE, result.rejectionReason());
        assertEquals(Optional.empty(), result.payload());
    }

    private static void assertRejected(
        BoundedFragmentAssembler.Result result,
        BoundedFragmentAssembler.RejectionReason reason
    ) {
        assertEquals(BoundedFragmentAssembler.Outcome.REJECTED, result.outcome());
        assertEquals(reason, result.rejectionReason());
        assertEquals(Optional.empty(), result.payload());
    }
}
