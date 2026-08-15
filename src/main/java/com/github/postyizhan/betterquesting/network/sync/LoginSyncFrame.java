package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHelloCodec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable protocol envelope used only by the login synchronization coordinator. */
public final class LoginSyncFrame {
    public static final int MAX_PAYLOAD_BYTES = Math.max(
        HandshakeHelloCodec.MAX_ENCODED_BYTES,
        LoginSettingsSnapshotCodec.MAX_ENCODED_BYTES);

    public enum Direction {
        CLIENT_TO_SERVER,
        SERVER_TO_CLIENT
    }

    public enum Type {
        CLIENT_HELLO,
        SERVER_HELLO,
        SETTINGS
    }

    private final Direction direction;
    private final Type type;
    private final UUID connectionToken;
    private final byte[] payload;

    public LoginSyncFrame(
        Direction direction,
        Type type,
        UUID connectionToken,
        byte[] payload
    ) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.type = Objects.requireNonNull(type, "type");
        this.connectionToken = Objects.requireNonNull(connectionToken, "connectionToken");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("login sync frame payload exceeds bound");
        }
        this.payload = payload.clone();
    }

    public static LoginSyncFrame clientHello(HandshakeHello hello) {
        Objects.requireNonNull(hello, "hello");
        return new LoginSyncFrame(
            Direction.CLIENT_TO_SERVER,
            Type.CLIENT_HELLO,
            hello.connectionToken(),
            HandshakeHelloCodec.encode(hello));
    }

    public static LoginSyncFrame serverHello(HandshakeHello hello) {
        Objects.requireNonNull(hello, "hello");
        return new LoginSyncFrame(
            Direction.SERVER_TO_CLIENT,
            Type.SERVER_HELLO,
            hello.connectionToken(),
            HandshakeHelloCodec.encode(hello));
    }

    public static LoginSyncFrame settings(UUID connectionToken, LoginSettingsSnapshot snapshot) {
        Objects.requireNonNull(connectionToken, "connectionToken");
        return new LoginSyncFrame(
            Direction.SERVER_TO_CLIENT,
            Type.SETTINGS,
            connectionToken,
            LoginSettingsSnapshotCodec.encode(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public Direction direction() {
        return direction;
    }

    public Type type() {
        return type;
    }

    public UUID connectionToken() {
        return connectionToken;
    }

    public UUID token() {
        return connectionToken;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public Optional<HandshakeHello> hello() {
        if (type == Type.SETTINGS) {
            return Optional.empty();
        }
        return HandshakeHelloCodec.decode(payload);
    }

    public Optional<LoginSettingsSnapshot> settings() {
        if (type != Type.SETTINGS) {
            return Optional.empty();
        }
        return LoginSettingsSnapshotCodec.decode(payload);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoginSyncFrame)) {
            return false;
        }
        LoginSyncFrame that = (LoginSyncFrame) other;
        return direction == that.direction
            && type == that.type
            && connectionToken.equals(that.connectionToken)
            && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(direction, type, connectionToken);
        return 31 * result + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "LoginSyncFrame[direction=" + direction + ", type=" + type
            + ", connectionToken=" + connectionToken + ", payloadLength=" + payload.length + ']';
    }
}
