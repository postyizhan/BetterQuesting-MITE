package com.github.postyizhan.betterquesting.network;

import java.util.Arrays;
import java.util.Objects;

public final class QuestingPacket {
    private static final int ENVELOPE_HEADER_BYTES = 2;

    private final String id;
    private final byte[] payload;

    public QuestingPacket(String id, byte[] payload) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payload, "payload");
        if (!isValidId(id)) {
            throw new IllegalArgumentException("invalid questing packet ID: " + id);
        }
        if ((long) ENVELOPE_HEADER_BYTES + id.length() + payload.length > PacketLimits.MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("questing packet envelope exceeds "
                + PacketLimits.MAX_ENVELOPE_BYTES + " bytes");
        }
        this.payload = payload.clone();
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public byte[] getPayload() {
        return payload();
    }

    int payloadLength() {
        return payload.length;
    }

    void copyPayloadTo(byte[] destination, int offset) {
        System.arraycopy(payload, 0, destination, offset, payload.length);
    }

    static boolean isValidId(String id) {
        if (id == null || id.length() < PacketLimits.MIN_ID_BYTES || id.length() > PacketLimits.MAX_ID_BYTES) {
            return false;
        }

        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1 || separator != id.lastIndexOf(':')) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (character > 0x7f) {
                return false;
            }
            if (index == separator) {
                continue;
            }
            if (index < separator ? !isNamespaceCharacter(character) : !isPathCharacter(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespaceCharacter(char character) {
        return isLowercaseLetterOrDigit(character) || character == '_' || character == '.' || character == '-';
    }

    private static boolean isPathCharacter(char character) {
        return isNamespaceCharacter(character) || character == '/';
    }

    private static boolean isLowercaseLetterOrDigit(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QuestingPacket packet)) {
            return false;
        }
        return id.equals(packet.id) && Arrays.equals(payload, packet.payload);
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "QuestingPacket[id=" + id + ", payloadLength=" + payload.length + ']';
    }
}
