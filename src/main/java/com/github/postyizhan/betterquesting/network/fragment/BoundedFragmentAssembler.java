package com.github.postyizhan.betterquesting.network.fragment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reassembles fragments for exactly one connection session.
 *
 * <p>Instances are single-thread-owned and are not internally synchronized. The connection owner
 * must close and discard its instance on disconnect or server stop; a closed instance cannot be
 * reused after reconnect.</p>
 */
public final class BoundedFragmentAssembler implements AutoCloseable {
    // Charge payload twice (held fragments plus completion output), then account for array slots.
    private static final long ASSEMBLY_ARRAY_OVERHEAD_BYTES = 32L;
    private static final long PER_FRAGMENT_OVERHEAD_BYTES = 48L;

    private final FragmentAssemblyLimits limits;
    private final Map<Long, TransferState> activeTransfers = new HashMap<Long, TransferState>();
    private final Map<Long, Long> retiredTransfers = new HashMap<Long, Long>();
    private long reservedBytes;
    private long lastObservedNanos;
    private boolean hasObservedTime;
    private boolean closed;

    public BoundedFragmentAssembler(FragmentAssemblyLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Result accept(QuestingFragment fragment, long nowNanos) {
        ensureOpen();
        Objects.requireNonNull(fragment, "fragment");
        maintainAt(nowNanos);

        long transferId = fragment.transferId();
        if (retiredTransfers.containsKey(transferId)) {
            return Result.rejected(RejectionReason.RETIRED_TRANSFER_ID);
        }

        TransferState active = activeTransfers.get(transferId);
        if (active != null) {
            return acceptActive(active, fragment, nowNanos);
        }
        return startTransfer(fragment, nowNanos);
    }

    public int expireIdle(long nowNanos) {
        ensureOpen();
        return maintainAt(nowNanos);
    }

    public FragmentAssemblyLimits limits() {
        return limits;
    }

    public FragmentAssemblyLimits getLimits() {
        return limits;
    }

    public int activeTransferCount() {
        return activeTransfers.size();
    }

    public int getActiveTransferCount() {
        return activeTransferCount();
    }

    public int retiredTransferCount() {
        return retiredTransfers.size();
    }

    public int getRetiredTransferCount() {
        return retiredTransferCount();
    }

    public int trackedTransferIdCount() {
        return activeTransfers.size() + retiredTransfers.size();
    }

    public int getTrackedTransferIdCount() {
        return trackedTransferIdCount();
    }

    public long reservedBytes() {
        return reservedBytes;
    }

    public long getReservedBytes() {
        return reservedBytes;
    }

    public boolean isRetired(long transferId) {
        return retiredTransfers.containsKey(transferId);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        activeTransfers.clear();
        retiredTransfers.clear();
        reservedBytes = 0L;
        closed = true;
    }

    private Result startTransfer(QuestingFragment fragment, long nowNanos) {
        RejectionReason invalid = validateNewFragment(fragment);
        if (invalid != RejectionReason.NONE) {
            return rejectNewTransfer(fragment.transferId(), invalid, nowNanos);
        }
        if (trackedTransferIdCount() >= limits.maxTrackedTransferIds()) {
            return Result.rejected(RejectionReason.TRACKED_ID_LIMIT_EXCEEDED);
        }
        if (activeTransfers.size() >= limits.maxConcurrentTransfers()) {
            return Result.rejected(RejectionReason.TOO_MANY_CONCURRENT_TRANSFERS);
        }
        long reservationCharge;
        try {
            reservationCharge = reservationCharge(fragment.totalLength(), fragment.fragmentCount());
        } catch (ArithmeticException overflow) {
            return rejectNewTransfer(fragment.transferId(), RejectionReason.IMPOSSIBLE_LAYOUT, nowNanos);
        }
        if (reservationCharge > limits.maxReservedBytes() - reservedBytes) {
            return Result.rejected(RejectionReason.RESERVED_BYTES_EXCEEDED);
        }

        TransferState state = new TransferState(
            fragment.totalLength(),
            fragment.fragmentCount(),
            reservationCharge,
            nowNanos
        );
        activeTransfers.put(fragment.transferId(), state);
        reservedBytes += reservationCharge;
        return storeDistinctFragment(state, fragment, fragment.ownedBytesForAssembly(), nowNanos);
    }

    private Result acceptActive(TransferState state, QuestingFragment fragment, long nowNanos) {
        if (fragment.totalLength() != state.totalLength || fragment.fragmentCount() != state.fragmentCount) {
            retireActive(fragment.transferId(), nowNanos);
            return Result.rejected(RejectionReason.METADATA_MISMATCH);
        }
        byte[] bytes = fragment.ownedBytesForAssembly();
        if (fragment.byteLength() > limits.maxFragmentBytes()) {
            retireActive(fragment.transferId(), nowNanos);
            return Result.rejected(RejectionReason.FRAGMENT_TOO_LARGE);
        }

        byte[] existing = state.fragments[fragment.fragmentIndex()];
        if (existing != null) {
            if (Arrays.equals(existing, bytes)) {
                return Result.duplicate();
            }
            retireActive(fragment.transferId(), nowNanos);
            return Result.rejected(RejectionReason.CONFLICTING_DUPLICATE);
        }

        int remainingFragments = state.fragmentCount - state.receivedCount - 1;
        long nextByteCount = (long) state.receivedBytes + bytes.length;
        long minimumFinalBytes = nextByteCount + remainingFragments;
        long maximumFinalBytes = nextByteCount
            + (long) remainingFragments * limits.maxFragmentBytes();
        if (state.totalLength < minimumFinalBytes || state.totalLength > maximumFinalBytes) {
            retireActive(fragment.transferId(), nowNanos);
            return Result.rejected(RejectionReason.BYTE_SUM_MISMATCH);
        }

        return storeDistinctFragment(state, fragment, bytes, nowNanos);
    }

    private Result storeDistinctFragment(
        TransferState state,
        QuestingFragment fragment,
        byte[] bytes,
        long nowNanos
    ) {
        state.fragments[fragment.fragmentIndex()] = bytes;
        state.receivedCount++;
        state.receivedBytes += bytes.length;
        state.lastProgressNanos = nowNanos;
        if (state.receivedCount != state.fragmentCount) {
            return Result.accepted();
        }
        if (state.receivedBytes != state.totalLength) {
            retireActive(fragment.transferId(), nowNanos);
            return Result.rejected(RejectionReason.BYTE_SUM_MISMATCH);
        }

        byte[] assembled = new byte[state.totalLength];
        int offset = 0;
        for (byte[] part : state.fragments) {
            System.arraycopy(part, 0, assembled, offset, part.length);
            offset += part.length;
        }
        retireActive(fragment.transferId(), nowNanos);
        return Result.completed(assembled);
    }

    private RejectionReason validateNewFragment(QuestingFragment fragment) {
        int byteLength = fragment.byteLength();
        if (byteLength > limits.maxFragmentBytes()) {
            return RejectionReason.FRAGMENT_TOO_LARGE;
        }
        if (fragment.totalLength() > limits.maxTransferBytes()) {
            return RejectionReason.TRANSFER_TOO_LARGE;
        }
        if (fragment.fragmentCount() > limits.maxFragmentsPerTransfer()) {
            return RejectionReason.TOO_MANY_FRAGMENTS;
        }

        long maximumLayoutBytes = (long) fragment.fragmentCount() * limits.maxFragmentBytes();
        if (fragment.totalLength() > maximumLayoutBytes
            || fragment.fragmentCount() > fragment.totalLength()) {
            return RejectionReason.IMPOSSIBLE_LAYOUT;
        }

        int remainingFragments = fragment.fragmentCount() - 1;
        long minimumFinalBytes = (long) byteLength + remainingFragments;
        long maximumFinalBytes = (long) byteLength
            + (long) remainingFragments * limits.maxFragmentBytes();
        if (fragment.totalLength() < minimumFinalBytes
            || fragment.totalLength() > maximumFinalBytes) {
            return RejectionReason.IMPOSSIBLE_LAYOUT;
        }
        return RejectionReason.NONE;
    }

    private Result rejectNewTransfer(long transferId, RejectionReason reason, long nowNanos) {
        if (trackedTransferIdCount() >= limits.maxTrackedTransferIds()) {
            return Result.rejected(RejectionReason.TRACKED_ID_LIMIT_EXCEEDED);
        }
        retiredTransfers.put(transferId, nowNanos);
        return Result.rejected(reason);
    }

    private void retireActive(long transferId, long nowNanos) {
        TransferState removed = activeTransfers.remove(transferId);
        if (removed == null) {
            return;
        }
        reservedBytes -= removed.reservationCharge;
        retiredTransfers.put(transferId, nowNanos);
    }

    private int maintainAt(long nowNanos) {
        if (hasObservedTime && nowNanos < lastObservedNanos) {
            throw new IllegalArgumentException("monotonic time regressed");
        }
        lastObservedNanos = nowNanos;
        hasObservedTime = true;

        Iterator<Map.Entry<Long, Long>> retired = retiredTransfers.entrySet().iterator();
        while (retired.hasNext()) {
            Map.Entry<Long, Long> entry = retired.next();
            if (hasElapsed(nowNanos, entry.getValue())) {
                retired.remove();
            }
        }

        int expired = 0;
        Iterator<Map.Entry<Long, TransferState>> active = activeTransfers.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<Long, TransferState> entry = active.next();
            TransferState state = entry.getValue();
            if (!hasElapsed(nowNanos, state.lastProgressNanos)) {
                continue;
            }
            active.remove();
            reservedBytes -= state.reservationCharge;
            retiredTransfers.put(entry.getKey(), nowNanos);
            expired++;
        }
        return expired;
    }

    private boolean hasElapsed(long nowNanos, long sinceNanos) {
        long elapsed = nowNanos - sinceNanos;
        return elapsed < 0L || elapsed >= limits.idleTimeoutNanos();
    }

    private long reservationCharge(int totalLength, int fragmentCount) {
        long payloadAndCompletion = Math.addExact((long) totalLength, (long) totalLength);
        long fragmentOverhead = Math.multiplyExact((long) fragmentCount, PER_FRAGMENT_OVERHEAD_BYTES);
        return Math.addExact(
            Math.addExact(payloadAndCompletion, ASSEMBLY_ARRAY_OVERHEAD_BYTES),
            fragmentOverhead
        );
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("fragment assembler is closed");
        }
    }

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        COMPLETED,
        REJECTED
    }

    public enum RejectionReason {
        NONE,
        FRAGMENT_TOO_LARGE,
        TRANSFER_TOO_LARGE,
        TOO_MANY_FRAGMENTS,
        IMPOSSIBLE_LAYOUT,
        TOO_MANY_CONCURRENT_TRANSFERS,
        RESERVED_BYTES_EXCEEDED,
        TRACKED_ID_LIMIT_EXCEEDED,
        RETIRED_TRANSFER_ID,
        METADATA_MISMATCH,
        CONFLICTING_DUPLICATE,
        BYTE_SUM_MISMATCH
    }

    public static final class Result {
        private final Outcome outcome;
        private final RejectionReason rejectionReason;
        private final byte[] payload;

        private Result(Outcome outcome, RejectionReason rejectionReason, byte[] payload) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
            this.payload = payload;
        }

        private static Result accepted() {
            return new Result(Outcome.ACCEPTED, RejectionReason.NONE, null);
        }

        private static Result duplicate() {
            return new Result(Outcome.DUPLICATE, RejectionReason.NONE, null);
        }

        private static Result completed(byte[] payload) {
            return new Result(Outcome.COMPLETED, RejectionReason.NONE, payload);
        }

        private static Result rejected(RejectionReason reason) {
            return new Result(Outcome.REJECTED, reason, null);
        }

        public Outcome outcome() {
            return outcome;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public RejectionReason rejectionReason() {
            return rejectionReason;
        }

        public RejectionReason getRejectionReason() {
            return rejectionReason;
        }

        public Optional<byte[]> payload() {
            return payload == null ? Optional.empty() : Optional.of(payload.clone());
        }

        public Optional<byte[]> getPayload() {
            return payload();
        }

        public boolean isCompleted() {
            return outcome == Outcome.COMPLETED;
        }

        public boolean isRejected() {
            return outcome == Outcome.REJECTED;
        }

        @Override
        public String toString() {
            return "BoundedFragmentAssembler.Result[outcome=" + outcome
                + ", rejectionReason=" + rejectionReason
                + ", payloadLength=" + (payload == null ? 0 : payload.length) + ']';
        }
    }

    private static final class TransferState {
        private final int totalLength;
        private final int fragmentCount;
        private final long reservationCharge;
        private final byte[][] fragments;
        private int receivedCount;
        private int receivedBytes;
        private long lastProgressNanos;

        private TransferState(int totalLength, int fragmentCount, long reservationCharge, long nowNanos) {
            this.totalLength = totalLength;
            this.fragmentCount = fragmentCount;
            this.reservationCharge = reservationCharge;
            this.fragments = new byte[fragmentCount][];
            this.lastProgressNanos = nowNanos;
        }
    }
}
