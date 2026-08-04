package com.github.postyizhan.betterquesting.api.storage;

import java.util.List;
import net.minecraft.NBTBase;

public interface INBTPartial<T extends NBTBase, K> {
    T writeToNBT(T nbt, List<K> subset);

    void readFromNBT(T nbt, boolean merge);
}
