package com.github.postyizhan.betterquesting.api.storage;

import java.util.UUID;
import net.minecraft.NBTTagCompound;

public interface ILifeDatabase extends INBTPartial<NBTTagCompound, UUID> {
    int getLives(UUID uuid);

    void setLives(UUID uuid, int value);

    void reset();
}
