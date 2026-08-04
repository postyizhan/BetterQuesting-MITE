package com.github.postyizhan.betterquesting.api.storage;

import net.minecraft.NBTBase;

public interface INBTSaveLoad<T extends NBTBase> {
    T writeToNBT(T nbt);

    void readFromNBT(T nbt);
}
