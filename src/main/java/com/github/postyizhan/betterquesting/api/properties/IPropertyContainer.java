package com.github.postyizhan.betterquesting.api.properties;

import net.minecraft.NBTTagCompound;

public interface IPropertyContainer {
    <T> T getProperty(IPropertyType<T> prop);

    <T> T getProperty(IPropertyType<T> prop, T def);

    default <T> T getOrDefault(IPropertyType<T> prop, T def) {
        T value = getProperty(prop);
        return value != null ? value : def;
    }

    boolean hasProperty(IPropertyType<?> prop);

    void removeProperty(IPropertyType<?> prop);

    <T> void setProperty(IPropertyType<T> prop, T value);

    void removeAllProps();

    NBTTagCompound writeToNBT(NBTTagCompound nbt);

    void readFromNBT(NBTTagCompound nbt);
}
