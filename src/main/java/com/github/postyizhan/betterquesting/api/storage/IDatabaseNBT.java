package com.github.postyizhan.betterquesting.api.storage;

import net.minecraft.NBTBase;

public interface IDatabaseNBT<T, E extends NBTBase, K extends NBTBase>
    extends IDatabase<T>, INBTPartial<E, Integer>, INBTProgress<K> {
}
