package betterquesting.api.storage;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import betterquesting.api2.storage.INBTPartial;

public interface ILifeDatabase extends INBTPartial<NBTTagCompound, UUID> {

    int getLives(UUID uuid);

    void setLives(UUID uuid, int value);

    void reset();
}
