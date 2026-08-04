package com.github.postyizhan.betterquesting.api.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.NBTBase;

public interface INBTProgress<T extends NBTBase> {
    T writeProgressToNBT(T nbt, List<UUID> users);

    void readProgressFromNBT(T nbt, boolean merge);
}
