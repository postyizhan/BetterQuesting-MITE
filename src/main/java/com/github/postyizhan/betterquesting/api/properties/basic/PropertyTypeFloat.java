package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.NbtNumbers;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagFloat;

public class PropertyTypeFloat extends PropertyTypeBase<Float> {
    public PropertyTypeFloat(ResourceKey key, Float defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public Float readValue(NBTBase nbt) {
        if (!NbtNumbers.isNumeric(nbt)) {
            return getDefault();
        }
        try {
            return NbtNumbers.readAsFloat(nbt);
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(Float value) {
        return new NBTTagFloat("", value == null ? getDefault() : value);
    }
}
