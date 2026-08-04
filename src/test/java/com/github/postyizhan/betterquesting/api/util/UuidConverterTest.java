package com.github.postyizhan.betterquesting.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidConverterTest {
    private static final UUID FIXTURE = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test
    void encodesUsingUpstreamCompatibleUrlBase64() {
        assertEquals("ABEiM0RVZneImaq7zN3u_w==", UuidConverter.encodeUuid(FIXTURE));
        assertEquals(FIXTURE, UuidConverter.decodeUuid(UuidConverter.encodeUuid(FIXTURE)));
    }

    @Test
    void stripsPaddingAndStillDecodes() {
        String encoded = UuidConverter.encodeUuidStripPadding(FIXTURE);
        assertEquals("ABEiM0RVZneImaq7zN3u_w", encoded);
        assertFalse(encoded.contains("="));
        assertEquals(FIXTURE, UuidConverter.decodeUuid(encoded));
    }

    @Test
    void convertsLegacyIdsAndRejectsNegativeIds() {
        assertEquals(new UUID(0L, 42L), UuidConverter.convertLegacyId(42));
        assertThrows(IllegalArgumentException.class, () -> UuidConverter.convertLegacyId(-1));
    }
}
