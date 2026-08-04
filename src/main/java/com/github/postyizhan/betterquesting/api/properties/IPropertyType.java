package com.github.postyizhan.betterquesting.api.properties;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;

public interface IPropertyType<T> {
    ResourceKey getKey();

    T getDefault();

    T readValue(NBTBase nbt);

    NBTBase writeValue(T value);
}
