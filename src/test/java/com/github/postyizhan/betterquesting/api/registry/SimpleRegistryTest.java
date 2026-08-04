package com.github.postyizhan.betterquesting.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.ResourceLocation;
import org.junit.jupiter.api.Test;

class SimpleRegistryTest {
    @Test
    void registersAndCreatesFromVerifiedResourceLocationKeys() {
        SimpleRegistry<IFactory<String>, String> registry = new SimpleRegistry<>();
        ResourceLocation id = new ResourceLocation("betterquesting", "fixture");
        IFactory<String> factory = new IFactory<>() {
            @Override
            public ResourceLocation getRegistryName() {
                return id;
            }

            @Override
            public String createNew() {
                return "created";
            }
        };

        registry.register(factory);
        assertEquals(factory, registry.getFactory(new ResourceLocation("betterquesting:fixture")));
        assertEquals("created", registry.createNew(id));
        assertEquals(java.util.List.of(factory), registry.getAll());
        assertNull(registry.createNew(new ResourceLocation("betterquesting", "missing")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(factory));
    }
}
