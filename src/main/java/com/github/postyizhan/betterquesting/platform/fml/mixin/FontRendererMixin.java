package com.github.postyizhan.betterquesting.platform.fml.mixin;

import com.github.postyizhan.betterquesting.platform.fml.client.FontRendererCompatibility;
import java.util.Random;
import net.minecraft.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FontRenderer.class)
public class FontRendererMixin {
    /**
     * FishModLoader expands ChatAllowedCharacters.allowedCharacters from the
     * resource-backed vanilla list. Only the first 224 entries have backing
     * slots in FontRenderer.charWidth[256], with the existing +32 offset.
     * Returning -1 keeps larger entries on the normal Unicode glyph path.
     */
    @Redirect(
        method = "getCharWidth(C)I",
        at = @At(value = "INVOKE", target = "Ljava/lang/String;indexOf(I)I")
    )
    private int betterquesting$normalizeWidthCharacterIndex(String allowedCharacters, int character) {
        return FontRendererCompatibility.normalizeAllowedCharacterIndex(allowedCharacters.indexOf(character));
    }

    @Redirect(
        method = "renderStringAtPos(Ljava/lang/String;Z)V",
        at = @At(value = "INVOKE", target = "Ljava/lang/String;indexOf(I)I")
    )
    private int betterquesting$normalizeRenderCharacterIndex(String allowedCharacters, int character) {
        return FontRendererCompatibility.normalizeAllowedCharacterIndex(allowedCharacters.indexOf(character));
    }

    @Redirect(
        method = "renderStringAtPos(Ljava/lang/String;Z)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I")
    )
    private int betterquesting$capObfuscatedCharacterBound(Random random, int bound) {
        return random.nextInt(FontRendererCompatibility.capRandomBound(bound));
    }
}
