package com.github.postyizhan.betterquesting.api.properties.basic;

import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagString;

public class PropertyTypeEnum<E extends Enum<E>> extends PropertyTypeBase<E> {
    private final Class<E> enumClass;

    public PropertyTypeEnum(ResourceKey key, E defaultValue) {
        super(key, defaultValue);
        this.enumClass = defaultValue.getDeclaringClass();
    }

    @Override
    public E readValue(NBTBase nbt) {
        if (nbt == null || nbt.getId() != 8) {
            return getDefault();
        }
        try {
            return Enum.valueOf(enumClass, ((NBTTagString) nbt).data);
        } catch (Exception ignored) {
            return getDefault();
        }
    }

    @Override
    public NBTBase writeValue(E value) {
        E actual = value == null ? getDefault() : value;
        return new NBTTagString("", actual.toString());
    }
}
