package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagByte;

public class PropertyTypeByte extends PropertyTypeBase<Byte> {
    public PropertyTypeByte(ResourceKey key, Byte defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public Byte readValue(NBTBase nbt) {
        if (!NbtNumbers.isNumeric(nbt)) {
            return getDefault();
        }
        try {
            return NbtNumbers.readAsByte(nbt);
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(Byte value) {
        return new NBTTagByte("", value == null ? getDefault() : value);
    }
}
