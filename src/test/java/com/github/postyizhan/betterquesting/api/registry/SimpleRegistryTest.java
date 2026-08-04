package com.github.postyizhan.betterquesting.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import org.junit.jupiter.api.Test;

class SimpleRegistryTest {
    @Test
    void registersAndCreatesFromPlatformNeutralKeys() {
        SimpleRegistry<IFactory<String>, String> registry = new SimpleRegistry<>();
        ResourceKey id = new ResourceKey("betterquesting", "fixture");
        IFactory<String> factory = new IFactory<>() {
            @Override
            public ResourceKey getRegistryName() {
                return id;
            }

            @Override
            public String createNew() {
                return "created";
            }
        };

        registry.register(factory);
        assertEquals(factory, registry.getFactory(ResourceKey.parse("betterquesting:fixture")));
        assertEquals("created", registry.createNew(id));
        assertEquals(java.util.List.of(factory), registry.getAll());
        assertNull(registry.createNew(new ResourceKey("betterquesting", "missing")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(factory));
    }
}
