package com.github.postyizhan.betterquesting.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceKeyTest {
    @Test
    void parseReplicatesMiteSeparatorBranches() {
        ResourceKey standard = ResourceKey.parse("BetterQuesting:Path/Kept");
        assertEquals("betterquesting", standard.getDomain());
        assertEquals("Path/Kept", standard.getPath());

        assertEquals(new ResourceKey("minecraft", "tail"), ResourceKey.parse("a:tail"));
        assertEquals(new ResourceKey("minecraft", "tail"), ResourceKey.parse(":tail"));
        assertEquals(new ResourceKey("minecraft", "Path/Kept"), ResourceKey.parse("Path/Kept"));
    }

    @Test
    void parseCoversMiteEmptyAndSeparatorBoundaryBranches() {
        ResourceKey empty = ResourceKey.parse("");
        assertEquals("minecraft", empty.getDomain());
        assertEquals("", empty.getPath());
        assertEquals("minecraft:", empty.toString());

        ResourceKey domainOnly = ResourceKey.parse("domain:");
        assertEquals("domain", domainOnly.getDomain());
        assertEquals("", domainOnly.getPath());

        ResourceKey leadingDoubleSeparator = ResourceKey.parse("::path");
        assertEquals("minecraft", leadingDoubleSeparator.getDomain());
        assertEquals(":path", leadingDoubleSeparator.getPath());

        ResourceKey oneCharacterDomain = ResourceKey.parse("a::b");
        assertEquals("minecraft", oneCharacterDomain.getDomain());
        assertEquals(":b", oneCharacterDomain.getPath());
    }

    @Test
    void directConstructorDoesNotNormalizeDomainAndSupportsValueIdentity() {
        ResourceKey direct = new ResourceKey("BetterQuesting", "Path");
        assertEquals("BetterQuesting", direct.getDomain());
        assertEquals("Path", direct.getPath());
        assertEquals("BetterQuesting", direct.getResourceDomain());
        assertEquals("Path", direct.getResourcePath());
        assertEquals(direct, new ResourceKey("BetterQuesting", "Path"));
        assertFalse(direct.equals(ResourceKey.parse("betterquesting:Path")));

        ResourceKey parsed = ResourceKey.parse("BetterQuesting:name");
        ResourceKey directMixedCase = new ResourceKey("BetterQuesting", "name");
        assertEquals("BetterQuesting", directMixedCase.getDomain());
        assertEquals("betterquesting", parsed.getDomain());
        assertFalse(directMixedCase.equals(parsed));
        assertNotEquals(directMixedCase.hashCode(), parsed.hashCode());
        assertEquals("minecraft", new ResourceKey(null, "name").getDomain());

        Map<ResourceKey, String> values = new HashMap<>();
        values.put(new ResourceKey("domain", "path"), "value");
        assertEquals("value", values.get(new ResourceKey("domain", "path")));
        assertEquals(ResourceKey.parse("betterquesting:Path"), ResourceKey.parse("betterquesting:Path"));
        assertEquals("betterquesting:Path", ResourceKey.parse("betterquesting:Path").toString());
    }

    @Test
    void constructorRejectsNullPath() {
        assertThrows(NullPointerException.class, () -> new ResourceKey("domain", null));
    }
}
