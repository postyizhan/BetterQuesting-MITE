package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.storage.IQuestSettings;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable server-authored QuestSettings values sent during one login session. */
public record LoginSettingsSnapshot(
    String packName,
    int packVersion,
    boolean partyEnabled,
    boolean editMode,
    boolean hardcore,
    int defaultLives,
    int maximumLives,
    String homeImage,
    float homeAnchorX,
    float homeAnchorY,
    int homeOffsetX,
    int homeOffsetY
) {
    public static final String FORMAT_ID = "betterquesting:login_settings";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_PACK_NAME_BYTES = 256;
    public static final int MAX_HOME_IMAGE_BYTES = 512;

    public LoginSettingsSnapshot {
        validateValues(
            packName,
            packVersion,
            defaultLives,
            maximumLives,
            homeImage,
            homeAnchorX,
            homeAnchorY);
    }

    void validateForWire() {
        validateValues(
            packName,
            packVersion,
            defaultLives,
            maximumLives,
            homeImage,
            homeAnchorX,
            homeAnchorY);
    }

    private static void validateValues(
        String packName,
        int packVersion,
        int defaultLives,
        int maximumLives,
        String homeImage,
        float homeAnchorX,
        float homeAnchorY
    ) {
        validateUtf8(packName, MAX_PACK_NAME_BYTES, "packName");
        validateUtf8(homeImage, MAX_HOME_IMAGE_BYTES, "homeImage");
        if (packVersion < 0) {
            throw new IllegalArgumentException("packVersion must be non-negative");
        }
        if (defaultLives < 0) {
            throw new IllegalArgumentException("defaultLives must be non-negative");
        }
        if (maximumLives < 0) {
            throw new IllegalArgumentException("maximumLives must be non-negative");
        }
        validateAnchor(homeAnchorX, "homeAnchorX");
        validateAnchor(homeAnchorY, "homeAnchorY");
    }

    public static LoginSettingsSnapshot capture(IQuestSettings settings) {
        Objects.requireNonNull(settings, "settings");
        LoginSettingsSnapshot snapshot = new LoginSettingsSnapshot(
            property(settings, NativeProps.PACK_NAME),
            property(settings, NativeProps.PACK_VER),
            property(settings, NativeProps.PARTY_ENABLE),
            property(settings, NativeProps.EDIT_MODE),
            property(settings, NativeProps.HARDCORE),
            property(settings, NativeProps.LIVES_DEF),
            property(settings, NativeProps.LIVES_MAX),
            property(settings, NativeProps.HOME_IMAGE),
            property(settings, NativeProps.HOME_ANC_X),
            property(settings, NativeProps.HOME_ANC_Y),
            property(settings, NativeProps.HOME_OFF_X),
            property(settings, NativeProps.HOME_OFF_Y));
        snapshot.validateForWire();
        return snapshot;
    }

    public String formatId() {
        return FORMAT_ID;
    }

    public int formatVersion() {
        return FORMAT_VERSION;
    }

    private static <T> T property(IQuestSettings settings, IPropertyType<T> property) {
        T value = settings.getProperty(property);
        return value != null ? value : Objects.requireNonNull(
            property.getDefault(), property.getKey() + " has no wire value or default");
    }

    private static void validateUtf8(String value, int maximumBytes, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximumBytes) {
            throw new IllegalArgumentException(name + " exceeds " + maximumBytes + " bytes");
        }
        try {
            int encodedBytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
                .remaining();
            if (encodedBytes > maximumBytes) {
                throw new IllegalArgumentException(name + " exceeds " + maximumBytes + " bytes");
            }
        } catch (CharacterCodingException invalidUtf16) {
            throw new IllegalArgumentException(name + " is not valid UTF-8 text", invalidUtf16);
        }
    }

    private static void validateAnchor(float value, String name) {
        if (!Float.isFinite(value) || value < 0F || value > 1F) {
            throw new IllegalArgumentException(name + " must be finite and between zero and one");
        }
    }
}
