package com.github.postyizhan.betterquesting.network.fragment;

import java.util.Arrays;
import java.util.Objects;

public final class QuestingFragment {
    private final long transferId;
    private final int totalLength;
    private final int fragmentIndex;
    private final int fragmentCount;
    private final byte[] bytes;

    public QuestingFragment(
        long transferId,
        int totalLength,
        int fragmentIndex,
        int fragmentCount,
        byte[] bytes
    ) {
        Objects.requireNonNull(bytes, "bytes");
        if (totalLength <= 0) {
            throw new IllegalArgumentException("totalLength must be positive");
        }
        if (fragmentCount <= 0) {
            throw new IllegalArgumentException("fragmentCount must be positive");
        }
        if (fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
            throw new IllegalArgumentException("fragmentIndex must identify a fragment");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("fragment bytes must not be empty");
        }

        this.transferId = transferId;
        this.totalLength = totalLength;
        this.fragmentIndex = fragmentIndex;
        this.fragmentCount = fragmentCount;
        this.bytes = bytes.clone();
    }

    public long transferId() {
        return transferId;
    }

    public long getTransferId() {
        return transferId;
    }

    public int totalLength() {
        return totalLength;
    }

    public int getTotalLength() {
        return totalLength;
    }

    public int fragmentIndex() {
        return fragmentIndex;
    }

    public int getFragmentIndex() {
        return fragmentIndex;
    }

    public int fragmentCount() {
        return fragmentCount;
    }

    public int getFragmentCount() {
        return fragmentCount;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public byte[] getBytes() {
        return bytes();
    }

    int byteLength() {
        return bytes.length;
    }

    byte[] ownedBytesForAssembly() {
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QuestingFragment)) {
            return false;
        }
        QuestingFragment that = (QuestingFragment) other;
        return transferId == that.transferId
            && totalLength == that.totalLength
            && fragmentIndex == that.fragmentIndex
            && fragmentCount == that.fragmentCount
            && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(transferId, totalLength, fragmentIndex, fragmentCount);
        return 31 * result + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "QuestingFragment[transferId=" + transferId
            + ", totalLength=" + totalLength
            + ", fragmentIndex=" + fragmentIndex
            + ", fragmentCount=" + fragmentCount
            + ", byteLength=" + bytes.length + ']';
    }
}
