package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LoginSettingsSnapshotCodecTest {
    @Test
    void encodesGoldenVectorAndRoundTripsDeterministically() {
        LoginSettingsSnapshot snapshot = new LoginSettingsSnapshot(
            "", 0, true, false, true, 1, 1, "a:b", 0F, 1F, -1, 1);
        byte[] expected = {
            'B', 'Q', 'S', 'S', 0x01,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x05,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x03, 'a', ':', 'b',
            0x00, 0x00, 0x00, 0x00,
            0x3f, (byte) 0x80, 0x00, 0x00,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            0x00, 0x00, 0x00, 0x01
        };

        assertArrayEquals(expected, LoginSettingsSnapshotCodec.encode(snapshot));
        assertEquals(snapshot, LoginSettingsSnapshotCodec.decode(expected).orElseThrow());
        assertArrayEquals(expected, LoginSettingsSnapshotCodec.encode(
            LoginSettingsSnapshotCodec.decode(expected).orElseThrow()));
        assertEquals("betterquesting:login_settings", snapshot.formatId());
        assertEquals(1, snapshot.formatVersion());
    }

    @Test
    void acceptsProtocolStringBoundariesAndFullWidthScalarValues() {
        String packName = "\u754c".repeat(85) + "p";
        String homeImage = "\ud83d\ude00".repeat(128);
        LoginSettingsSnapshot boundary = new LoginSettingsSnapshot(
            packName,
            Integer.MAX_VALUE,
            true,
            true,
            true,
            Integer.MAX_VALUE,
            0,
            homeImage,
            0F,
            1F,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE);

        byte[] encoded = LoginSettingsSnapshotCodec.encode(boundary);

        assertEquals(LoginSettingsSnapshotCodec.MAX_ENCODED_BYTES, encoded.length);
        assertEquals(boundary, LoginSettingsSnapshotCodec.decode(encoded).orElseThrow());
        assertTrue(LoginSettingsSnapshotCodec.decode(
            new byte[LoginSettingsSnapshotCodec.MAX_ENCODED_BYTES + 1]).isEmpty());
    }

    @Test
    void rejectsTextThatExceedsItsUtf8ByteLimitOrContainsInvalidUtf16() {
        String oversizedMultibyteName = "\u754c".repeat(85) + "pp";

        assertTrue(oversizedMultibyteName.length() < LoginSettingsSnapshot.MAX_PACK_NAME_BYTES);
        assertThrows(IllegalArgumentException.class,
            () -> copy(oversizedMultibyteName, 0, 1, 1, "a:b", 0F, 0F, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> copy("\ud800", 0, 1, 1, "a:b", 0F, 0F, 0, 0));
    }

    @Test
    void rejectsOversizedDeclaredStringWithinGlobalPacketLimit() {
        String packName = "p".repeat(LoginSettingsSnapshot.MAX_PACK_NAME_BYTES);
        byte[] encoded = LoginSettingsSnapshotCodec.encode(copy(
            packName, 0, 1, 1, "a:b", 0F, 0F, 0, 0));
        int insertionOffset = 7 + LoginSettingsSnapshot.MAX_PACK_NAME_BYTES;
        byte[] oversizedDeclaration = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, oversizedDeclaration, 0, insertionOffset);
        oversizedDeclaration[insertionOffset] = 'x';
        System.arraycopy(encoded, insertionOffset, oversizedDeclaration, insertionOffset + 1,
            encoded.length - insertionOffset);
        int declaredLength = LoginSettingsSnapshot.MAX_PACK_NAME_BYTES + 1;
        oversizedDeclaration[5] = (byte) (declaredLength >>> 8);
        oversizedDeclaration[6] = (byte) declaredLength;

        assertTrue(oversizedDeclaration.length < LoginSettingsSnapshotCodec.MAX_ENCODED_BYTES);
        assertTrue(LoginSettingsSnapshotCodec.decode(oversizedDeclaration).isEmpty());
    }

    @Test
    void rejectsNullTruncationTrailingBytesAndUnknownFormat() {
        byte[] encoded = LoginSettingsSnapshotCodec.encode(sample());

        assertTrue(LoginSettingsSnapshotCodec.decode(null).isEmpty());
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginSettingsSnapshotCodec.decode(Arrays.copyOf(encoded, length)).isEmpty(),
                "accepted truncation at " + length);
        }

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertTrue(LoginSettingsSnapshotCodec.decode(trailing).isEmpty());

        byte[] unknownVersion = encoded.clone();
        unknownVersion[4] = 2;
        assertTrue(LoginSettingsSnapshotCodec.decode(unknownVersion).isEmpty());

        byte[] unknownMagic = encoded.clone();
        unknownMagic[0] = 'X';
        assertTrue(LoginSettingsSnapshotCodec.decode(unknownMagic).isEmpty());
    }

    @Test
    void rejectsNonCanonicalFlagsMalformedUtf8AndFloats() {
        LoginSettingsSnapshot snapshot = sample();
        byte[] encoded = LoginSettingsSnapshotCodec.encode(snapshot);
        int packBytes = snapshot.packName().getBytes(StandardCharsets.UTF_8).length;
        int flagsOffset = 7 + packBytes + Integer.BYTES;

        byte[] unknownFlag = encoded.clone();
        unknownFlag[flagsOffset] |= 0x08;
        assertTrue(LoginSettingsSnapshotCodec.decode(unknownFlag).isEmpty());

        byte[] malformedUtf8 = encoded.clone();
        malformedUtf8[7] = (byte) 0xc0;
        assertTrue(LoginSettingsSnapshotCodec.decode(malformedUtf8).isEmpty());

        int homeLengthOffset = flagsOffset + 1 + Integer.BYTES * 2;
        int homeBytes = snapshot.homeImage().getBytes(StandardCharsets.UTF_8).length;
        int anchorXOffset = homeLengthOffset + 2 + homeBytes;
        byte[] nanAnchor = encoded.clone();
        nanAnchor[anchorXOffset] = 0x7f;
        nanAnchor[anchorXOffset + 1] = (byte) 0xc0;
        nanAnchor[anchorXOffset + 2] = 0x00;
        nanAnchor[anchorXOffset + 3] = 0x00;
        assertTrue(LoginSettingsSnapshotCodec.decode(nanAnchor).isEmpty());
    }

    @Test
    void rejectsUnsafeNumericValuesDecodedFromTheWire() {
        LoginSettingsSnapshot snapshot = sample();
        byte[] encoded = LoginSettingsSnapshotCodec.encode(snapshot);
        int packBytes = snapshot.packName().getBytes(StandardCharsets.UTF_8).length;
        int packVersionOffset = 7 + packBytes;
        int flagsOffset = packVersionOffset + Integer.BYTES;
        int defaultLivesOffset = flagsOffset + 1;
        int maximumLivesOffset = defaultLivesOffset + Integer.BYTES;
        int homeLengthOffset = maximumLivesOffset + Integer.BYTES;
        int homeBytes = snapshot.homeImage().getBytes(StandardCharsets.UTF_8).length;
        int anchorXOffset = homeLengthOffset + Short.BYTES + homeBytes;

        assertRejectedInt(encoded, packVersionOffset, -1);
        assertRejectedInt(encoded, defaultLivesOffset, -1);
        assertRejectedInt(encoded, maximumLivesOffset, -1);
        assertRejectedFloat(encoded, anchorXOffset, Float.POSITIVE_INFINITY);
        assertRejectedFloat(encoded, anchorXOffset, -0.01F);
        assertRejectedFloat(encoded, anchorXOffset, 1.01F);
    }

    @Test
    void validatesValuesBeforeEncoding() {
        assertThrows(NullPointerException.class, () -> LoginSettingsSnapshotCodec.encode(null));
        assertThrows(NullPointerException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy(null, 0, 1, 1, "a:b", 0F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy(
                "p".repeat(LoginSettingsSnapshot.MAX_PACK_NAME_BYTES + 1), 0, 1, 1,
                "a:b", 0F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy("pack", -1, 1, 1, "a:b", 0F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy("pack", 0, -1, 1, "a:b", 0F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy("pack", 0, 1, -1, "a:b", 0F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy("pack", 0, 1, 1, "a:b", -0.1F, 0F, 0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshotCodec.encode(copy(
                "pack", 0, 1, 1, "a:b", 0F, Float.NaN, 0, 0)));
    }

    @Test
    void snapshotConstructionRejectsInvalidNumericState() {
        assertThrows(IllegalArgumentException.class,
            () -> copy("pack", -1, 1, 1, "a:b", 0F, 0F, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> copy("pack", 0, -1, 1, "a:b", 0F, 0F, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> copy("pack", 0, 1, -1, "a:b", 0F, 0F, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> copy("pack", 0, 1, 1, "a:b", Float.NaN, 0F, 0, 0));
    }

    @Test
    void capturesAndRoundTripsReachableLegacySettingsExactly() {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "Legacy Pack");
        settings.setProperty(NativeProps.PACK_VER, Integer.MAX_VALUE);
        settings.setProperty(NativeProps.LIVES_DEF, 2_000_001);
        settings.setProperty(NativeProps.LIVES_MAX, 0);
        settings.setProperty(NativeProps.HOME_IMAGE, "Textures/GUI/Legacy Title.PNG:variant");
        settings.setProperty(NativeProps.HOME_OFF_X, Integer.MIN_VALUE);
        settings.setProperty(NativeProps.HOME_OFF_Y, Integer.MAX_VALUE);

        LoginSettingsSnapshot captured = LoginSettingsSnapshot.capture(settings);

        assertEquals(2_000_001, captured.defaultLives());
        assertEquals(0, captured.maximumLives());
        assertEquals("Textures/GUI/Legacy Title.PNG:variant", captured.homeImage());
        assertEquals(Integer.MIN_VALUE, captured.homeOffsetX());
        assertEquals(Integer.MAX_VALUE, captured.homeOffsetY());
        assertEquals(captured, LoginSettingsSnapshotCodec.decode(
            LoginSettingsSnapshotCodec.encode(captured)).orElseThrow());
    }

    @Test
    void captureUsesNativeDefaultsForNullableAbsentProperties() {
        QuestSettings settings = new QuestSettings() {
            @Override
            public <T> T getProperty(IPropertyType<T> property) {
                return null;
            }
        };

        LoginSettingsSnapshot captured = LoginSettingsSnapshot.capture(settings);
        LoginSettingsSnapshot defaults = new LoginSettingsSnapshot(
            "", 0, true, true, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);

        assertEquals(defaults, captured);
        assertEquals(defaults, LoginSettingsSnapshotCodec.decode(
            LoginSettingsSnapshotCodec.encode(captured)).orElseThrow());
    }

    @Test
    void captureFailsClosedWhenAPropertyCannotFitTheWireFormat() {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME,
            "p".repeat(LoginSettingsSnapshot.MAX_PACK_NAME_BYTES + 1));

        assertThrows(IllegalArgumentException.class,
            () -> LoginSettingsSnapshot.capture(settings));
    }

    @Test
    void captureAndCodecDoNotRetainMutableSourceOrEncodedState() {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "Original");
        settings.setProperty(NativeProps.PACK_VER, 7);
        LoginSettingsSnapshot captured = LoginSettingsSnapshot.capture(settings);

        settings.setProperty(NativeProps.PACK_NAME, "Changed");
        byte[] encoded = LoginSettingsSnapshotCodec.encode(captured);
        LoginSettingsSnapshot decoded = LoginSettingsSnapshotCodec.decode(encoded).orElseThrow();
        Arrays.fill(encoded, (byte) 0);

        assertEquals("Original", captured.packName());
        assertEquals(captured, decoded);
        assertArrayEquals(LoginSettingsSnapshotCodec.encode(captured),
            LoginSettingsSnapshotCodec.encode(decoded));
    }

    private static LoginSettingsSnapshot sample() {
        return new LoginSettingsSnapshot(
            "Pack", 7, true, false, true, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.25F, 0.75F, -128, 8);
    }

    private static LoginSettingsSnapshot copy(
        String packName,
        int packVersion,
        int defaultLives,
        int maximumLives,
        String homeImage,
        float homeAnchorX,
        float homeAnchorY,
        int homeOffsetX,
        int homeOffsetY
    ) {
        return new LoginSettingsSnapshot(
            packName, packVersion, true, true, false, defaultLives, maximumLives,
            homeImage, homeAnchorX, homeAnchorY, homeOffsetX, homeOffsetY);
    }

    private static void assertRejectedInt(byte[] encoded, int offset, int value) {
        byte[] mutated = encoded.clone();
        ByteBuffer.wrap(mutated).putInt(offset, value);
        assertTrue(LoginSettingsSnapshotCodec.decode(mutated).isEmpty());
    }

    private static void assertRejectedFloat(byte[] encoded, int offset, float value) {
        byte[] mutated = encoded.clone();
        ByteBuffer.wrap(mutated).putFloat(offset, value);
        assertTrue(LoginSettingsSnapshotCodec.decode(mutated).isEmpty());
    }
}
