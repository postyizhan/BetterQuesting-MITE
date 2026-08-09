package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.NbtNumbers;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagInt;

public class PropertyTypeInteger extends PropertyTypeBase<Integer> {
    public PropertyTypeInteger(ResourceKey key, Integer defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public Integer readValue(NBTBase nbt) {
        if (!NbtNumbers.isNumeric(nbt)) {
            return getDefault();
        }
        try {
            return NbtNumbers.readAsInt(nbt);
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(Integer value) {
        return new NBTTagInt("", value == null ? getDefault() : value);
    }
}
