package com.github.postyizhan.betterquesting.network.sync;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/** Strict fixed-width codec for an authoritative signed Life count. */
public final class LoginLifeSnapshotCodec {
    private static final int MAGIC = 0x42514c53;
    public static final int MAX_ENCODED_BYTES = Integer.BYTES + 1 + Integer.BYTES;

    private LoginLifeSnapshotCodec() {
    }

    public static byte[] encode(LoginLifeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return ByteBuffer.allocate(MAX_ENCODED_BYTES)
            .putInt(MAGIC)
            .put((byte) LoginLifeSnapshot.FORMAT_VERSION)
            .putInt(snapshot.lives())
            .array();
    }

    public static Optional<LoginLifeSnapshot> decode(byte[] encoded) {
        if (encoded == null || encoded.length != MAX_ENCODED_BYTES) {
            return Optional.empty();
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        if (input.getInt() != MAGIC
            || Byte.toUnsignedInt(input.get()) != LoginLifeSnapshot.FORMAT_VERSION) {
            return Optional.empty();
        }
        LoginLifeSnapshot snapshot = new LoginLifeSnapshot(input.getInt());
        return input.hasRemaining() ? Optional.empty() : Optional.of(snapshot);
    }
}
