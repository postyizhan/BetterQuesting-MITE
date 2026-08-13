package com.github.postyizhan.betterquesting.platform.fml.mixin;

/**
 * Keeps FishModLoader's expanded allowed-character list compatible with the
 * vanilla default font table.
 */
final class FontRendererCompatibility {
    static final int DEFAULT_FONT_TABLE_LENGTH = 256;
    static final int DEFAULT_FONT_INDEX_OFFSET = 32;

    private FontRendererCompatibility() {
    }

    static int normalizeAllowedCharacterIndex(int index) {
        return isDefaultFontIndex(index) ? index : -1;
    }

    static boolean isDefaultFontIndex(int index) {
        return index >= 0 && index < DEFAULT_FONT_TABLE_LENGTH - DEFAULT_FONT_INDEX_OFFSET;
    }

    static int capRandomBound(int bound) {
        return Math.min(bound, DEFAULT_FONT_TABLE_LENGTH - DEFAULT_FONT_INDEX_OFFSET);
    }
}
