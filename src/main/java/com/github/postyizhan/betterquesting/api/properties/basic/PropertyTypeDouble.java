package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagDouble;

public class PropertyTypeDouble extends PropertyTypeBase<Double> {
    public PropertyTypeDouble(ResourceKey key, Double defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public Double readValue(NBTBase nbt) {
        if (!NbtNumbers.isNumeric(nbt)) {
            return getDefault();
        }
        try {
            return NbtNumbers.readAsDouble(nbt);
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(Double value) {
        return new NBTTagDouble("", value == null ? getDefault() : value);
    }
}
