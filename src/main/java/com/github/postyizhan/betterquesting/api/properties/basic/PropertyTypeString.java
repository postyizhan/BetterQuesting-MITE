package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagString;

public class PropertyTypeString extends PropertyTypeBase<String> {
    public PropertyTypeString(ResourceKey key, String defaultValue) {
        super(key, defaultValue);
    }

    @Override
    public String readValue(NBTBase nbt) {
        if (nbt == null || nbt.getId() != 8) {
            return getDefault();
        }
        try {
            return ((NBTTagString) nbt).data;
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(String value) {
        return new NBTTagString("", value == null ? getDefault() : value);
    }
}
