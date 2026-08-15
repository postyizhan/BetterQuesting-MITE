package com.github.postyizhan.betterquesting.network;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class QuestingPacketCodec {
    public static final int WIRE_VERSION = 1;
    private static final int HEADER_BYTES = 2;

    private QuestingPacketCodec() {
    }

    public static byte[] encode(QuestingPacket packet) {
        Objects.requireNonNull(packet, "packet");
        byte[] id = packet.id().getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new byte[HEADER_BYTES + id.length + packet.payloadLength()];
        encoded[0] = WIRE_VERSION;
        encoded[1] = (byte) id.length;
        System.arraycopy(id, 0, encoded, HEADER_BYTES, id.length);
        packet.copyPayloadTo(encoded, HEADER_BYTES + id.length);
        return encoded;
    }

    public static Optional<QuestingPacket> decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES || encoded.length > PacketLimits.MAX_ENVELOPE_BYTES) {
            return Optional.empty();
        }
        if ((encoded[0] & 0xff) != WIRE_VERSION) {
            return Optional.empty();
        }

        int idLength = encoded[1] & 0xff;
        if (idLength < PacketLimits.MIN_ID_BYTES || idLength > PacketLimits.MAX_ID_BYTES
            || encoded.length < HEADER_BYTES + idLength) {
            return Optional.empty();
        }
        for (int index = HEADER_BYTES; index < HEADER_BYTES + idLength; index++) {
            if ((encoded[index] & 0x80) != 0) {
                return Optional.empty();
            }
        }

        String id = new String(encoded, HEADER_BYTES, idLength, StandardCharsets.US_ASCII);
        if (!QuestingPacket.isValidId(id)) {
            return Optional.empty();
        }
        byte[] payload = Arrays.copyOfRange(encoded, HEADER_BYTES + idLength, encoded.length);
        return Optional.of(new QuestingPacket(id, payload));
    }
}
