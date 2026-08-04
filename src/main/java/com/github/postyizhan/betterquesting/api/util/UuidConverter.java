package com.github.postyizhan.betterquesting.api.util;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

public final class UuidConverter {
    private UuidConverter() {
    }

    public static UUID convertLegacyId(int legacyId) {
        if (legacyId < 0) {
            throw new IllegalArgumentException();
        }
        return new UUID(0L, legacyId);
    }

    public static String encodeUuid(UUID uuid) {
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    public static String encodeUuidStripPadding(UUID uuid) {
        return encodeUuid(uuid).replace("=", "");
    }

    public static UUID decodeUuid(String string) {
        ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(string));
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
