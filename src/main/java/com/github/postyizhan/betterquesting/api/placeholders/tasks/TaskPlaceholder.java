package com.github.postyizhan.betterquesting.api.placeholders.tasks;

import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

public class TaskPlaceholder implements ITask {
    private final NBTTagCompound nbtData = new NBTTagCompound();

    public void setTaskConfigData(NBTTagCompound nbt) {
        nbtData.setTag("orig_data", nbt);
    }

    public void setTaskProgressData(NBTTagCompound nbt) {
        nbtData.setTag("orig_prog", nbt);
    }

    public NBTTagCompound getTaskConfigData() {
        return nbtData.getCompoundTag("orig_data");
    }

    public NBTTagCompound getTaskProgressData() {
        return nbtData.getCompoundTag("orig_prog");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("orig_data", getTaskConfigData());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        nbtData.setTag("orig_data", nbt.getCompoundTag("orig_data"));
    }

    @Override
    public NBTTagCompound writeProgressToNBT(NBTTagCompound nbt, List<UUID> users) {
        nbt.setTag("orig_prog", getTaskProgressData());
        return nbt;
    }

    @Override
    public void readProgressFromNBT(NBTTagCompound nbt, boolean merge) {
        nbtData.setTag("orig_prog", nbt.getCompoundTag("orig_prog"));
    }

    @Override
    public String getUnlocalisedName() {
        return "betterquesting.placeholder";
    }

    @Override
    public ResourceKey getFactoryID() {
        return FactoryTaskPlaceholder.INSTANCE.getRegistryName();
    }

    @Override
    public boolean isComplete(UUID uuid) {
        return false;
    }

    @Override
    public void setComplete(UUID uuid) {
    }

    @Override
    public void resetUser(UUID uuid) {
    }
}
