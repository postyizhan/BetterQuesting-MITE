package com.github.postyizhan.betterquesting.network;

import java.util.Objects;

public final class NbtLimits {
    private final int maxDepth;
    private final long maxTotalNodes;
    private final int maxCompoundEntries;
    private final int maxListItems;
    private final int maxStringLength;
    private final int maxByteArrayLength;
    private final int maxIntArrayLength;
    private final long maxSerializedBytes;

    public NbtLimits(
        int maxDepth,
        long maxTotalNodes,
        int maxCompoundEntries,
        int maxListItems,
        int maxStringLength,
        int maxByteArrayLength,
        int maxIntArrayLength,
        long maxSerializedBytes
    ) {
        this.maxDepth = maxDepth;
        this.maxTotalNodes = maxTotalNodes;
        this.maxCompoundEntries = maxCompoundEntries;
        this.maxListItems = maxListItems;
        this.maxStringLength = maxStringLength;
        this.maxByteArrayLength = maxByteArrayLength;
        this.maxIntArrayLength = maxIntArrayLength;
        this.maxSerializedBytes = maxSerializedBytes;
        if (!isValid()) {
            throw new IllegalArgumentException("invalid NBT limits");
        }
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public long maxTotalNodes() {
        return maxTotalNodes;
    }

    public long getMaxTotalNodes() {
        return maxTotalNodes;
    }

    public int maxCompoundEntries() {
        return maxCompoundEntries;
    }

    public int getMaxCompoundEntries() {
        return maxCompoundEntries;
    }

    public int maxListItems() {
        return maxListItems;
    }

    public int getMaxListItems() {
        return maxListItems;
    }

    public int maxStringLength() {
        return maxStringLength;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public int maxByteArrayLength() {
        return maxByteArrayLength;
    }

    public int getMaxByteArrayLength() {
        return maxByteArrayLength;
    }

    public int maxIntArrayLength() {
        return maxIntArrayLength;
    }

    public int getMaxIntArrayLength() {
        return maxIntArrayLength;
    }

    public long maxSerializedBytes() {
        return maxSerializedBytes;
    }

    public long getMaxSerializedBytes() {
        return maxSerializedBytes;
    }

    boolean isValid() {
        return maxDepth > 0
            && maxTotalNodes > 0L
            && maxCompoundEntries >= 0
            && maxListItems >= 0
            && maxStringLength >= 0
            && maxByteArrayLength >= 0
            && maxIntArrayLength >= 0
            && maxSerializedBytes >= 0L;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NbtLimits)) {
            return false;
        }
        NbtLimits that = (NbtLimits) other;
        return maxDepth == that.maxDepth
            && maxTotalNodes == that.maxTotalNodes
            && maxCompoundEntries == that.maxCompoundEntries
            && maxListItems == that.maxListItems
            && maxStringLength == that.maxStringLength
            && maxByteArrayLength == that.maxByteArrayLength
            && maxIntArrayLength == that.maxIntArrayLength
            && maxSerializedBytes == that.maxSerializedBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            maxDepth,
            maxTotalNodes,
            maxCompoundEntries,
            maxListItems,
            maxStringLength,
            maxByteArrayLength,
            maxIntArrayLength,
            maxSerializedBytes
        );
    }

    @Override
    public String toString() {
        return "NbtLimits[maxDepth=" + maxDepth
            + ", maxTotalNodes=" + maxTotalNodes
            + ", maxCompoundEntries=" + maxCompoundEntries
            + ", maxListItems=" + maxListItems
            + ", maxStringLength=" + maxStringLength
            + ", maxByteArrayLength=" + maxByteArrayLength
            + ", maxIntArrayLength=" + maxIntArrayLength
            + ", maxSerializedBytes=" + maxSerializedBytes + ']';
    }
}
