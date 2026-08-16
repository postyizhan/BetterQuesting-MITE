package com.github.postyizhan.betterquesting.network.fragment;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/** Strict bounded wire codec for one questing fragment. */
public final class QuestingFragmentCodec {
    public static final int MAGIC = 0x42514631;
    public static final int WIRE_VERSION = 1;
    public static final int HEADER_BYTES = Integer.BYTES + 1 + Long.BYTES + Integer.BYTES * 4;

    private final FragmentAssemblyLimits limits;
    private final int maxEncodedBytes;

    public QuestingFragmentCodec(FragmentAssemblyLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        long encodedBound = (long) HEADER_BYTES + limits.maxFragmentBytes();
        if (encodedBound > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("fragment codec encoded bound exceeds array capacity");
        }
        this.maxEncodedBytes = (int) encodedBound;
    }

    public FragmentAssemblyLimits limits() {
        return limits;
    }

    public FragmentAssemblyLimits getLimits() {
        return limits;
    }

    public int maxEncodedBytes() {
        return maxEncodedBytes;
    }

    public int getMaxEncodedBytes() {
        return maxEncodedBytes;
    }

    public byte[] encode(QuestingFragment fragment) {
        Objects.requireNonNull(fragment, "fragment");
        byte[] payload = fragment.ownedBytesForAssembly();
        validateMetadata(
            fragment.totalLength(),
            fragment.fragmentIndex(),
            fragment.fragmentCount(),
            payload.length);

        ByteBuffer encoded = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        encoded.putInt(MAGIC);
        encoded.put((byte) WIRE_VERSION);
        encoded.putLong(fragment.transferId());
        encoded.putInt(fragment.totalLength());
        encoded.putInt(fragment.fragmentIndex());
        encoded.putInt(fragment.fragmentCount());
        encoded.putInt(payload.length);
        encoded.put(payload);
        return encoded.array();
    }

    public Optional<QuestingFragment> decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES || encoded.length > maxEncodedBytes) {
            return Optional.empty();
        }

        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
            if (input.getInt() != MAGIC || Byte.toUnsignedInt(input.get()) != WIRE_VERSION) {
                return Optional.empty();
            }

            long transferId = input.getLong();
            int totalLength = input.getInt();
            int fragmentIndex = input.getInt();
            int fragmentCount = input.getInt();
            int payloadLength = input.getInt();
            if (!isValidMetadata(totalLength, fragmentIndex, fragmentCount, payloadLength)
                || input.remaining() != payloadLength) {
                return Optional.empty();
            }

            byte[] payload = new byte[payloadLength];
            input.get(payload);
            if (input.hasRemaining()) {
                return Optional.empty();
            }
            return Optional.of(new QuestingFragment(
                transferId, totalLength, fragmentIndex, fragmentCount, payload));
        } catch (BufferUnderflowException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void validateMetadata(
        int totalLength,
        int fragmentIndex,
        int fragmentCount,
        int payloadLength
    ) {
        if (!isValidMetadata(totalLength, fragmentIndex, fragmentCount, payloadLength)) {
            throw new IllegalArgumentException("fragment metadata exceeds wire bounds");
        }
    }

    private boolean isValidMetadata(
        int totalLength,
        int fragmentIndex,
        int fragmentCount,
        int payloadLength
    ) {
        if (totalLength <= 0 || totalLength > limits.maxTransferBytes()
            || fragmentCount <= 0 || fragmentCount > limits.maxFragmentsPerTransfer()
            || fragmentIndex < 0 || fragmentIndex >= fragmentCount
            || payloadLength <= 0 || payloadLength > limits.maxFragmentBytes()) {
            return false;
        }

        long remainingFragments = (long) fragmentCount - 1L;
        long minimumTotalLength = (long) payloadLength + remainingFragments;
        long maximumTotalLength = (long) payloadLength
            + remainingFragments * limits.maxFragmentBytes();
        return fragmentCount <= totalLength
            && totalLength <= (long) fragmentCount * limits.maxFragmentBytes()
            && totalLength >= minimumTotalLength
            && totalLength <= maximumTotalLength;
    }
}
