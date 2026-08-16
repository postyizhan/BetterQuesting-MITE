package com.github.postyizhan.betterquesting.network.sync;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** Strict typed envelope codec for complete logical login bulk payloads. */
public final class LoginBulkPayloadCodec {
    private static final int MAGIC = 0x42514c50;
    private static final int ENVELOPE_VERSION = 1;
    private static final int MAX_ID_BYTES = 64;
    private static final byte[] LIFE_ID = LoginLifeSnapshot.FORMAT_ID.getBytes(
        StandardCharsets.UTF_8);
    private static final int FIXED_BYTES = Integer.BYTES + 1 + 1 + 1 + Integer.BYTES;
    public static final int MAX_ENCODED_BYTES = FIXED_BYTES + LIFE_ID.length
        + LoginLifeSnapshotCodec.MAX_ENCODED_BYTES;

    private LoginBulkPayloadCodec() {
    }

    public static byte[] encode(LoginBulkPayload payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] body = LoginLifeSnapshotCodec.encode(payload.life());
        return ByteBuffer.allocate(FIXED_BYTES + LIFE_ID.length + body.length)
            .putInt(MAGIC)
            .put((byte) ENVELOPE_VERSION)
            .put((byte) LIFE_ID.length)
            .put(LIFE_ID)
            .put((byte) payload.version())
            .putInt(body.length)
            .put(body)
            .array();
    }

    public static Optional<LoginBulkPayload> decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
            if (input.getInt() != MAGIC
                || Byte.toUnsignedInt(input.get()) != ENVELOPE_VERSION) {
                return Optional.empty();
            }
            int idLength = Byte.toUnsignedInt(input.get());
            if (idLength > MAX_ID_BYTES || input.remaining() < idLength) {
                return Optional.empty();
            }
            ByteBuffer idBytes = input.slice();
            idBytes.limit(idLength);
            String id = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(idBytes)
                .toString();
            input.position(input.position() + idLength);
            if (!LoginLifeSnapshot.FORMAT_ID.equals(id)
                || Byte.toUnsignedInt(input.get()) != LoginLifeSnapshot.FORMAT_VERSION) {
                return Optional.empty();
            }
            int bodyLength = input.getInt();
            if (bodyLength != LoginLifeSnapshotCodec.MAX_ENCODED_BYTES
                || input.remaining() != bodyLength) {
                return Optional.empty();
            }
            byte[] body = new byte[bodyLength];
            input.get(body);
            return LoginLifeSnapshotCodec.decode(body).map(LoginBulkPayload::life);
        } catch (BufferUnderflowException | CharacterCodingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
