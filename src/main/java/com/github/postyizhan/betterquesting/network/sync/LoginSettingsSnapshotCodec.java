package com.github.postyizhan.betterquesting.network.sync;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

public final class LoginSettingsSnapshotCodec {
    private static final int MAGIC = 0x42515353;
    private static final int FIXED_BYTES = 38;
    private static final int MIN_ENCODED_BYTES = FIXED_BYTES;
    private static final int PARTY_ENABLED_FLAG = 1;
    private static final int EDIT_MODE_FLAG = 1 << 1;
    private static final int HARDCORE_FLAG = 1 << 2;
    private static final int KNOWN_FLAGS = PARTY_ENABLED_FLAG | EDIT_MODE_FLAG | HARDCORE_FLAG;

    public static final int MAX_ENCODED_BYTES = FIXED_BYTES
        + LoginSettingsSnapshot.MAX_PACK_NAME_BYTES
        + LoginSettingsSnapshot.MAX_HOME_IMAGE_BYTES;

    private LoginSettingsSnapshotCodec() {
    }

    public static byte[] encode(LoginSettingsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshot.validateForWire();
        byte[] packName = encodeString(
            snapshot.packName(), LoginSettingsSnapshot.MAX_PACK_NAME_BYTES);
        byte[] homeImage = encodeString(
            snapshot.homeImage(), LoginSettingsSnapshot.MAX_HOME_IMAGE_BYTES);
        ByteBuffer encoded = ByteBuffer.allocate(FIXED_BYTES + packName.length + homeImage.length);
        encoded.putInt(MAGIC);
        encoded.put((byte) LoginSettingsSnapshot.FORMAT_VERSION);
        putString(encoded, packName);
        encoded.putInt(snapshot.packVersion());
        encoded.put(flags(snapshot));
        encoded.putInt(snapshot.defaultLives());
        encoded.putInt(snapshot.maximumLives());
        putString(encoded, homeImage);
        encoded.putFloat(snapshot.homeAnchorX());
        encoded.putFloat(snapshot.homeAnchorY());
        encoded.putInt(snapshot.homeOffsetX());
        encoded.putInt(snapshot.homeOffsetY());
        return encoded.array();
    }

    public static Optional<LoginSettingsSnapshot> decode(byte[] encoded) {
        if (encoded == null || encoded.length < MIN_ENCODED_BYTES || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }

        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
            if (input.getInt() != MAGIC
                || Byte.toUnsignedInt(input.get()) != LoginSettingsSnapshot.FORMAT_VERSION) {
                return Optional.empty();
            }

            String packName = readString(input, LoginSettingsSnapshot.MAX_PACK_NAME_BYTES);
            int packVersion = input.getInt();
            int flags = Byte.toUnsignedInt(input.get());
            if ((flags & ~KNOWN_FLAGS) != 0) {
                return Optional.empty();
            }
            int defaultLives = input.getInt();
            int maximumLives = input.getInt();
            String homeImage = readString(input, LoginSettingsSnapshot.MAX_HOME_IMAGE_BYTES);
            float homeAnchorX = input.getFloat();
            float homeAnchorY = input.getFloat();
            int homeOffsetX = input.getInt();
            int homeOffsetY = input.getInt();
            if (input.hasRemaining()) {
                return Optional.empty();
            }

            LoginSettingsSnapshot snapshot = new LoginSettingsSnapshot(
                packName,
                packVersion,
                (flags & PARTY_ENABLED_FLAG) != 0,
                (flags & EDIT_MODE_FLAG) != 0,
                (flags & HARDCORE_FLAG) != 0,
                defaultLives,
                maximumLives,
                homeImage,
                homeAnchorX,
                homeAnchorY,
                homeOffsetX,
                homeOffsetY);
            snapshot.validateForWire();
            return Optional.of(snapshot);
        } catch (BufferUnderflowException | CharacterCodingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static byte flags(LoginSettingsSnapshot snapshot) {
        int flags = snapshot.partyEnabled() ? PARTY_ENABLED_FLAG : 0;
        if (snapshot.editMode()) {
            flags |= EDIT_MODE_FLAG;
        }
        if (snapshot.hardcore()) {
            flags |= HARDCORE_FLAG;
        }
        return (byte) flags;
    }

    private static void putString(ByteBuffer output, byte[] value) {
        output.putShort((short) value.length);
        output.put(value);
    }

    private static byte[] encodeString(String value, int maximumBytes) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            if (encoded.remaining() > maximumBytes) {
                throw new IllegalArgumentException("bounded string exceeds UTF-8 byte limit");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalidText) {
            throw new IllegalArgumentException("bounded string contains invalid text", invalidText);
        }
    }

    private static String readString(ByteBuffer input, int maximumBytes) throws CharacterCodingException {
        int length = Short.toUnsignedInt(input.getShort());
        if (length > maximumBytes || input.remaining() < length) {
            throw new IllegalArgumentException("invalid bounded string length");
        }

        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        String value = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes)
            .toString();
        input.position(input.position() + length);
        return value;
    }
}
