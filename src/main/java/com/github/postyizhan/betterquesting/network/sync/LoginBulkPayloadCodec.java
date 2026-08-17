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
    private static final byte[] CHAPTER_ID = LoginChapterSnapshot.FORMAT_ID.getBytes(
        StandardCharsets.UTF_8);
    private static final byte[] LIFE_ID = LoginLifeSnapshot.FORMAT_ID.getBytes(
        StandardCharsets.UTF_8);
    private static final byte[] NAME_ID = LoginNameSnapshot.FORMAT_ID.getBytes(
        StandardCharsets.UTF_8);
    private static final int FIXED_BYTES = Integer.BYTES + 1 + 1 + 1 + Integer.BYTES;
    public static final int MAX_ENCODED_BYTES = FIXED_BYTES + Math.max(
        CHAPTER_ID.length + LoginChapterSnapshotCodec.MAX_ENCODED_BYTES,
        Math.max(
            LIFE_ID.length + LoginLifeSnapshotCodec.MAX_ENCODED_BYTES,
            NAME_ID.length + LoginNameSnapshotCodec.MAX_ENCODED_BYTES));

    private LoginBulkPayloadCodec() {
    }

    public static byte[] encode(LoginBulkPayload payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] id;
        byte[] body;
        if (payload.chapter() != null) {
            id = CHAPTER_ID;
            body = LoginChapterSnapshotCodec.encode(payload.chapter());
        } else if (payload.life() != null) {
            id = LIFE_ID;
            body = LoginLifeSnapshotCodec.encode(payload.life());
        } else {
            id = NAME_ID;
            body = LoginNameSnapshotCodec.encode(payload.name());
        }
        return ByteBuffer.allocate(FIXED_BYTES + id.length + body.length)
            .putInt(MAGIC)
            .put((byte) ENVELOPE_VERSION)
            .put((byte) id.length)
            .put(id)
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
            int expectedVersion;
            int maximumBodyLength;
            if (LoginChapterSnapshot.FORMAT_ID.equals(id)) {
                expectedVersion = LoginChapterSnapshot.FORMAT_VERSION;
                maximumBodyLength = LoginChapterSnapshotCodec.MAX_ENCODED_BYTES;
            } else if (LoginLifeSnapshot.FORMAT_ID.equals(id)) {
                expectedVersion = LoginLifeSnapshot.FORMAT_VERSION;
                maximumBodyLength = LoginLifeSnapshotCodec.MAX_ENCODED_BYTES;
            } else if (LoginNameSnapshot.FORMAT_ID.equals(id)) {
                expectedVersion = LoginNameSnapshot.FORMAT_VERSION;
                maximumBodyLength = LoginNameSnapshotCodec.MAX_ENCODED_BYTES;
            } else {
                return Optional.empty();
            }
            if (Byte.toUnsignedInt(input.get()) != expectedVersion) {
                return Optional.empty();
            }
            int bodyLength = input.getInt();
            if (bodyLength < 0 || bodyLength > maximumBodyLength
                || input.remaining() != bodyLength
                || (LoginLifeSnapshot.FORMAT_ID.equals(id)
                    && bodyLength != LoginLifeSnapshotCodec.MAX_ENCODED_BYTES)) {
                return Optional.empty();
            }
            byte[] body = new byte[bodyLength];
            input.get(body);
            if (LoginChapterSnapshot.FORMAT_ID.equals(id)) {
                return LoginChapterSnapshotCodec.decode(body).map(LoginBulkPayload::chapter);
            }
            return LoginLifeSnapshot.FORMAT_ID.equals(id)
                ? LoginLifeSnapshotCodec.decode(body).map(LoginBulkPayload::life)
                : LoginNameSnapshotCodec.decode(body).map(LoginBulkPayload::name);
        } catch (BufferUnderflowException | CharacterCodingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
