package com.github.postyizhan.betterquesting.platform.fml.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FontRendererCompatibilityTest {
    @Test
    void onlyDefaultFontTableSlotsRemainDefault() {
        assertTrue(FontRendererCompatibility.isDefaultFontIndex(0));
        assertTrue(FontRendererCompatibility.isDefaultFontIndex(1));
        assertTrue(FontRendererCompatibility.isDefaultFontIndex(223));
        assertFalse(FontRendererCompatibility.isDefaultFontIndex(224));
        assertFalse(FontRendererCompatibility.isDefaultFontIndex(7493));
        assertFalse(FontRendererCompatibility.isDefaultFontIndex(15125));
    }

    @Test
    void dangerousIndexesUseUnicodeSentinel() {
        assertEquals(-1, FontRendererCompatibility.normalizeAllowedCharacterIndex(7493));
        assertEquals(-1, FontRendererCompatibility.normalizeAllowedCharacterIndex(15125));
        assertEquals(-1, FontRendererCompatibility.normalizeAllowedCharacterIndex(-1));
        assertEquals(1, FontRendererCompatibility.normalizeAllowedCharacterIndex(1));
        assertEquals(223, FontRendererCompatibility.normalizeAllowedCharacterIndex(223));
    }

    @Test
    void obfuscatedSelectionCannotReachPastDefaultTable() {
        assertEquals(224, FontRendererCompatibility.capRandomBound(15126));
        assertEquals(224, FontRendererCompatibility.capRandomBound(224));
        assertEquals(12, FontRendererCompatibility.capRandomBound(12));
    }
}
