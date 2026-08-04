package com.github.postyizhan.betterquesting.api.properties.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.api.enums.EnumLogic;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagDouble;
import net.minecraft.NBTTagFloat;
import net.minecraft.NBTTagInt;
import net.minecraft.NBTTagLong;
import net.minecraft.NBTTagString;
import org.junit.jupiter.api.Test;

class PropertyTypesTest {
    private static final ResourceKey KEY = ResourceKey.parse("betterquesting:test");

    @Test
    void propertyTypesRoundTripDefaultsNullAndWrongTagIds() {
        PropertyTypeBoolean bool = new PropertyTypeBoolean(KEY, true);
        assertEquals(true, bool.readValue(null));
        assertEquals(true, bool.readValue(new NBTTagString("", "bad")));
        assertEquals(false, bool.readValue(bool.writeValue(false)));
        assertEquals(true, bool.readValue(bool.writeValue(null)));

        PropertyTypeByte bytes = new PropertyTypeByte(KEY, (byte) 7);
        assertEquals((byte) 7, bytes.readValue(null));
        assertEquals((byte) 7, bytes.readValue(new NBTTagString("", "bad")));
        assertEquals((byte) 12, bytes.readValue(bytes.writeValue((byte) 12)));
        assertEquals((byte) 7, bytes.readValue(bytes.writeValue(null)));

        PropertyTypeInteger integers = new PropertyTypeInteger(KEY, 7);
        assertEquals(7, integers.readValue(null));
        assertEquals(7, integers.readValue(new NBTTagString("", "bad")));
        assertEquals(12, integers.readValue(integers.writeValue(12)));
        assertEquals(7, integers.readValue(integers.writeValue(null)));

        PropertyTypeFloat floats = new PropertyTypeFloat(KEY, 1.5F);
        assertEquals(1.5F, floats.readValue(null));
        assertEquals(1.5F, floats.readValue(new NBTTagString("", "bad")));
        assertEquals(2.5F, floats.readValue(floats.writeValue(2.5F)));
        assertEquals(1.5F, floats.readValue(floats.writeValue(null)));

        PropertyTypeDouble doubles = new PropertyTypeDouble(KEY, 1.5D);
        assertEquals(1.5D, doubles.readValue(null));
        assertEquals(1.5D, doubles.readValue(new NBTTagString("", "bad")));
        assertEquals(2.5D, doubles.readValue(doubles.writeValue(2.5D)));
        assertEquals(1.5D, doubles.readValue(doubles.writeValue(null)));

        PropertyTypeString strings = new PropertyTypeString(KEY, "default");
        assertEquals("default", strings.readValue(null));
        assertEquals("default", strings.readValue(new NBTTagInt("", 1)));
        assertEquals("stored", strings.readValue(strings.writeValue("stored")));
        assertEquals("default", strings.readValue(strings.writeValue(null)));

        PropertyTypeEnum<EnumLogic> enums = new PropertyTypeEnum<>(KEY, EnumLogic.AND);
        assertEquals(EnumLogic.AND, enums.readValue(null));
        assertEquals(EnumLogic.AND, enums.readValue(new NBTTagInt("", 1)));
        assertEquals(EnumLogic.AND, enums.readValue(new NBTTagString("", "not-an-enum")));
        assertEquals(EnumLogic.XOR, enums.readValue(enums.writeValue(EnumLogic.XOR)));
        assertEquals(EnumLogic.AND, enums.readValue(enums.writeValue(null)));
    }

    @Test
    void numericReadsKeepUpstreamNarrowingSemantics() {
        PropertyTypeInteger integers = new PropertyTypeInteger(KEY, 0);
        PropertyTypeByte bytes = new PropertyTypeByte(KEY, (byte) 0);
        PropertyTypeBoolean bool = new PropertyTypeBoolean(KEY, false);

        assertEquals(-4, integers.readValue(new NBTTagDouble("", -3.7D)));
        assertEquals((byte) -4, bytes.readValue(new NBTTagDouble("", -3.7D)));
        assertEquals(-4, integers.readValue(new NBTTagFloat("", -3.7F)));
        assertEquals(3, integers.readValue(new NBTTagDouble("", 3.7D)));
        assertEquals((byte) 44, bytes.readValue(new NBTTagInt("", 300)));
        assertEquals(false, bool.readValue(new NBTTagInt("", 256)));
        assertEquals(true, bool.readValue(new NBTTagInt("", 1)));
        assertEquals(-1, integers.readValue(new NBTTagLong("", Long.MAX_VALUE)));
    }

    @Test
    void compoundIsRejectedByEveryNumericType() {
        NBTBase compound = new NBTTagCompound();
        assertEquals(false, new PropertyTypeBoolean(KEY, false).readValue(compound));
        assertEquals((byte) 2, new PropertyTypeByte(KEY, (byte) 2).readValue(compound));
        assertEquals(3, new PropertyTypeInteger(KEY, 3).readValue(compound));
        assertEquals(4F, new PropertyTypeFloat(KEY, 4F).readValue(compound));
        assertEquals(5D, new PropertyTypeDouble(KEY, 5D).readValue(compound));
    }
}
