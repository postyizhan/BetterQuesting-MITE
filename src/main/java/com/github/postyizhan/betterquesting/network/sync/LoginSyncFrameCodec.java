package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHelloCodec;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

/** Strict bounded codec for hello and settings login frames. */
public final class LoginSyncFrameCodec {
    private static final int MAGIC = 0x42514c31;
    private static final int WIRE_VERSION = 1;
    private static final int HEADER_BYTES = LoginSyncProtocol.LOGIN_FRAME_HEADER_BYTES;
    public static final int MAX_ENCODED_BYTES = HEADER_BYTES + LoginSyncFrame.MAX_PAYLOAD_BYTES;

    private LoginSyncFrameCodec() {
    }

    public static byte[] encode(LoginSyncFrame frame) {
        if (frame == null) {
            throw new NullPointerException("frame");
        }
        byte[] payload = frame.payload();
        if (payload.length > LoginSyncFrame.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("login sync frame payload exceeds bound");
        }
        if (!isCanonicalPair(frame.type(), frame.direction())
            || !isCanonicalPayload(frame.type(), frame.connectionToken(), payload)) {
            throw new IllegalArgumentException("login sync frame payload does not match its type");
        }

        ByteBuffer encoded = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        encoded.putInt(MAGIC);
        encoded.put((byte) WIRE_VERSION);
        encoded.put((byte) frame.type().ordinal());
        encoded.put((byte) frame.direction().ordinal());
        encoded.putLong(frame.connectionToken().getMostSignificantBits());
        encoded.putLong(frame.connectionToken().getLeastSignificantBits());
        encoded.putInt(payload.length);
        encoded.put(payload);
        return encoded.array();
    }

    public static Optional<LoginSyncFrame> decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }

        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
            if (input.getInt() != MAGIC || Byte.toUnsignedInt(input.get()) != WIRE_VERSION) {
                return Optional.empty();
            }
            int typeId = Byte.toUnsignedInt(input.get());
            int directionId = Byte.toUnsignedInt(input.get());
            if (typeId >= LoginSyncFrame.Type.values().length
                || directionId >= LoginSyncFrame.Direction.values().length) {
                return Optional.empty();
            }
            LoginSyncFrame.Type type = LoginSyncFrame.Type.values()[typeId];
            LoginSyncFrame.Direction direction = LoginSyncFrame.Direction.values()[directionId];
            if (!isCanonicalPair(type, direction)) {
                return Optional.empty();
            }
            UUID token = new UUID(input.getLong(), input.getLong());
            int payloadLength = input.getInt();
            if (payloadLength < 0 || payloadLength > LoginSyncFrame.MAX_PAYLOAD_BYTES
                || input.remaining() != payloadLength) {
                return Optional.empty();
            }
            byte[] payload = new byte[payloadLength];
            input.get(payload);
            if (!isCanonicalPayload(type, token, payload)) {
                return Optional.empty();
            }
            return Optional.of(new LoginSyncFrame(direction, type, token, payload));
        } catch (BufferUnderflowException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static boolean isCanonicalPayload(
        LoginSyncFrame.Type type,
        UUID connectionToken,
        byte[] payload
    ) {
        if (type == LoginSyncFrame.Type.SETTINGS) {
            return LoginSettingsSnapshotCodec.decode(payload).isPresent();
        }
        if (type == LoginSyncFrame.Type.BULK_FRAGMENT) {
            return LoginSyncProtocol.FRAGMENT_CODEC.decode(payload).isPresent();
        }
        Optional<HandshakeHello> hello = HandshakeHelloCodec.decode(payload);
        return hello.isPresent() && connectionToken.equals(hello.orElseThrow().connectionToken());
    }

    private static boolean isCanonicalPair(
        LoginSyncFrame.Type type,
        LoginSyncFrame.Direction direction
    ) {
        return (type == LoginSyncFrame.Type.CLIENT_HELLO
                && direction == LoginSyncFrame.Direction.CLIENT_TO_SERVER)
            || ((type == LoginSyncFrame.Type.SERVER_HELLO
                || type == LoginSyncFrame.Type.SETTINGS
                || type == LoginSyncFrame.Type.BULK_FRAGMENT)
                && direction == LoginSyncFrame.Direction.SERVER_TO_CLIENT);
    }
}
