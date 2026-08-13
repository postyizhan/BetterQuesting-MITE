package com.github.postyizhan.betterquesting.platform.fml.client;

/**
 * Keeps FishModLoader's expanded allowed-character list compatible with the
 * vanilla default font table.
 */
public final class FontRendererCompatibility {
    public static final int DEFAULT_FONT_TABLE_LENGTH = 256;
    public static final int DEFAULT_FONT_INDEX_OFFSET = 32;

    private FontRendererCompatibility() {
    }

    public static int normalizeAllowedCharacterIndex(int index) {
        return isDefaultFontIndex(index) ? index : -1;
    }

    public static boolean isDefaultFontIndex(int index) {
        return index >= 0 && index < DEFAULT_FONT_TABLE_LENGTH - DEFAULT_FONT_INDEX_OFFSET;
    }

    public static int capRandomBound(int bound) {
        return Math.min(bound, DEFAULT_FONT_TABLE_LENGTH - DEFAULT_FONT_INDEX_OFFSET);
    }
}
