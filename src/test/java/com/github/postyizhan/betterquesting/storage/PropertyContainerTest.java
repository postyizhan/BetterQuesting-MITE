package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeInteger;
import com.github.postyizhan.betterquesting.api.properties.basic.PropertyTypeString;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class PropertyContainerTest {
    private static final PropertyTypeString NAME = new PropertyTypeString(ResourceKey.parse("betterquesting:name"), "default");
    private static final PropertyTypeInteger COUNT = new PropertyTypeInteger(ResourceKey.parse("other:count"), -1);
    private static final PropertyTypeInteger NESTED_LEFT = new PropertyTypeInteger(ResourceKey.parse("nested:left"), -1);
    private static final PropertyTypeInteger NESTED_RIGHT = new PropertyTypeInteger(ResourceKey.parse("nested:right"), -1);

    @Test
    void setsGetsRemovesAndClearsPropertiesByDomain() {
        PropertyContainer container = new PropertyContainer();
        assertEquals("default", container.getProperty(NAME));
        assertEquals("fallback", container.getProperty(null, "fallback"));
        assertFalse(container.hasProperty(NAME));

        container.setProperty(NAME, "stored");
        container.setProperty(COUNT, 6);
        assertTrue(container.hasProperty(NAME));
        assertEquals("stored", container.getProperty(NAME));
        assertEquals(6, container.getProperty(COUNT));

        NBTTagCompound serialized = container.writeToNBT(new NBTTagCompound());
        assertTrue(serialized.hasKey("betterquesting"));
        assertTrue(serialized.hasKey("other"));

        container.removeProperty(NAME);
        assertFalse(container.hasProperty(NAME));
        serialized = container.writeToNBT(new NBTTagCompound());
        assertFalse(serialized.hasKey("betterquesting"));
        assertTrue(serialized.hasKey("other"));

        container.removeAllProps();
        assertFalse(container.hasProperty(COUNT));
        assertTrue(container.writeToNBT(new NBTTagCompound()).hasNoTags());
    }

    @Test
    void nbtRoundTripUsesDeepCopyAndMergesNestedDomains() {
        PropertyContainer source = new PropertyContainer();
        source.setProperty(NAME, "source");
        source.setProperty(NESTED_LEFT, 1);

        NBTTagCompound persisted = source.writeToNBT(new NBTTagCompound());
        persisted.getCompoundTag("betterquesting").setString("name", "changed-after-write");
        assertEquals("source", source.getProperty(NAME));

        PropertyContainer restored = new PropertyContainer();
        restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));
        assertEquals("source", restored.getProperty(NAME));
        assertEquals(1, restored.getProperty(NESTED_LEFT));

        PropertyContainer overlay = new PropertyContainer();
        overlay.setProperty(NESTED_RIGHT, 2);
        NBTTagCompound target = source.writeToNBT(new NBTTagCompound());
        overlay.writeToNBT(target);
        PropertyContainer merged = new PropertyContainer();
        merged.readFromNBT(target);
        assertEquals(1, merged.getProperty(NESTED_LEFT));
        assertEquals(2, merged.getProperty(NESTED_RIGHT));

        PropertyContainer replacement = new PropertyContainer();
        replacement.setProperty(NAME, "replacement");
        replacement.writeToNBT(target);
        merged.readFromNBT(target);
        assertEquals("replacement", merged.getProperty(NAME));
    }
}
