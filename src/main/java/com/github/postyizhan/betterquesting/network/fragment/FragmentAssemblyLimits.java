package com.github.postyizhan.betterquesting.network.fragment;

import java.util.Objects;

public final class FragmentAssemblyLimits {
    private final int maxFragmentBytes;
    private final int maxTransferBytes;
    private final int maxFragmentsPerTransfer;
    private final int maxConcurrentTransfers;
    private final long maxReservedBytes;
    private final int maxTrackedTransferIds;
    private final long idleTimeoutNanos;

    public FragmentAssemblyLimits(
        int maxFragmentBytes,
        int maxTransferBytes,
        int maxFragmentsPerTransfer,
        int maxConcurrentTransfers,
        long maxReservedBytes,
        int maxTrackedTransferIds,
        long idleTimeoutNanos
    ) {
        if (maxFragmentBytes <= 0
            || maxTransferBytes <= 0
            || maxFragmentsPerTransfer <= 0
            || maxConcurrentTransfers <= 0
            || maxReservedBytes <= 0L
            || maxTrackedTransferIds <= 0
            || idleTimeoutNanos <= 0L) {
            throw new IllegalArgumentException("fragment assembly limits must be positive");
        }

        this.maxFragmentBytes = maxFragmentBytes;
        this.maxTransferBytes = maxTransferBytes;
        this.maxFragmentsPerTransfer = maxFragmentsPerTransfer;
        this.maxConcurrentTransfers = maxConcurrentTransfers;
        this.maxReservedBytes = maxReservedBytes;
        this.maxTrackedTransferIds = maxTrackedTransferIds;
        this.idleTimeoutNanos = idleTimeoutNanos;
    }

    public int maxFragmentBytes() {
        return maxFragmentBytes;
    }

    public int getMaxFragmentBytes() {
        return maxFragmentBytes;
    }

    public int maxTransferBytes() {
        return maxTransferBytes;
    }

    public int getMaxTransferBytes() {
        return maxTransferBytes;
    }

    public int maxFragmentsPerTransfer() {
        return maxFragmentsPerTransfer;
    }

    public int getMaxFragmentsPerTransfer() {
        return maxFragmentsPerTransfer;
    }

    public int maxConcurrentTransfers() {
        return maxConcurrentTransfers;
    }

    public int getMaxConcurrentTransfers() {
        return maxConcurrentTransfers;
    }

    public long maxReservedBytes() {
        return maxReservedBytes;
    }

    public long getMaxReservedBytes() {
        return maxReservedBytes;
    }

    public int maxTrackedTransferIds() {
        return maxTrackedTransferIds;
    }

    public int getMaxTrackedTransferIds() {
        return maxTrackedTransferIds;
    }

    public long idleTimeoutNanos() {
        return idleTimeoutNanos;
    }

    public long getIdleTimeoutNanos() {
        return idleTimeoutNanos;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FragmentAssemblyLimits)) {
            return false;
        }
        FragmentAssemblyLimits that = (FragmentAssemblyLimits) other;
        return maxFragmentBytes == that.maxFragmentBytes
            && maxTransferBytes == that.maxTransferBytes
            && maxFragmentsPerTransfer == that.maxFragmentsPerTransfer
            && maxConcurrentTransfers == that.maxConcurrentTransfers
            && maxReservedBytes == that.maxReservedBytes
            && maxTrackedTransferIds == that.maxTrackedTransferIds
            && idleTimeoutNanos == that.idleTimeoutNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            maxFragmentBytes,
            maxTransferBytes,
            maxFragmentsPerTransfer,
            maxConcurrentTransfers,
            maxReservedBytes,
            maxTrackedTransferIds,
            idleTimeoutNanos
        );
    }

    @Override
    public String toString() {
        return "FragmentAssemblyLimits[maxFragmentBytes=" + maxFragmentBytes
            + ", maxTransferBytes=" + maxTransferBytes
            + ", maxFragmentsPerTransfer=" + maxFragmentsPerTransfer
            + ", maxConcurrentTransfers=" + maxConcurrentTransfers
            + ", maxReservedBytes=" + maxReservedBytes
            + ", maxTrackedTransferIds=" + maxTrackedTransferIds
            + ", idleTimeoutNanos=" + idleTimeoutNanos + ']';
    }
}
