package com.github.postyizhan.betterquesting.network.handshake;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strict, fixed-size codec for the capability hello exchanged by a connection. */
public final class HandshakeHelloCodec {
    private static final int MAGIC = 0x42514831;
    private static final int MAGIC_BYTES = Integer.BYTES;
    private static final int VERSION_BYTES = 1;
    private static final int TOKEN_BYTES = Long.BYTES * 2;
    private static final int CAPABILITY_BYTES = Integer.BYTES * 2 + Long.BYTES * 2;

    public static final int ENCODED_BYTES = MAGIC_BYTES + VERSION_BYTES
        + TOKEN_BYTES + CAPABILITY_BYTES;
    public static final int MAX_ENCODED_BYTES = ENCODED_BYTES;
    public static final int WIRE_VERSION = 1;

    private HandshakeHelloCodec() {
    }

    public static byte[] encode(HandshakeHello hello) {
        Objects.requireNonNull(hello, "hello");
        ByteBuffer encoded = ByteBuffer.allocate(ENCODED_BYTES);
        UUID token = hello.connectionToken();
        HandshakeCapabilities capabilities = hello.capabilities();
        encoded.putInt(MAGIC);
        encoded.put((byte) WIRE_VERSION);
        encoded.putLong(token.getMostSignificantBits());
        encoded.putLong(token.getLeastSignificantBits());
        encoded.putInt(capabilities.protocolVersion());
        encoded.putInt(capabilities.dataFormatVersion());
        encoded.putLong(capabilities.supportedFeatureBits());
        encoded.putLong(capabilities.requiredFeatureBits());
        return encoded.array();
    }

    public static Optional<HandshakeHello> decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_BYTES
            || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }

        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
            if (input.getInt() != MAGIC || Byte.toUnsignedInt(input.get()) != WIRE_VERSION) {
                return Optional.empty();
            }
            UUID token = new UUID(input.getLong(), input.getLong());
            HandshakeCapabilities capabilities = new HandshakeCapabilities(
                input.getInt(),
                input.getInt(),
                input.getLong(),
                input.getLong());
            if (input.hasRemaining()) {
                return Optional.empty();
            }
            return Optional.of(new HandshakeHello(token, capabilities));
        } catch (BufferUnderflowException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
