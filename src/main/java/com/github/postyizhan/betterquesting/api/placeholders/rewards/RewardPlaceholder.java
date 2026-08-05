package com.github.postyizhan.betterquesting.api.placeholders.rewards;

import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTTagCompound;

public class RewardPlaceholder implements IReward {
    private NBTTagCompound nbtSaved = new NBTTagCompound();

    public void setRewardConfigData(NBTTagCompound nbt) {
        nbtSaved = nbt;
    }

    public NBTTagCompound getRewardConfigData() {
        return nbtSaved;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("orig_data", nbtSaved);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        nbtSaved = nbt.getCompoundTag("orig_data");
    }

    @Override
    public String getUnlocalisedName() {
        return "betterquesting.placeholder";
    }

    @Override
    public ResourceKey getFactoryID() {
        return FactoryRewardPlaceholder.INSTANCE.getRegistryName();
    }
}
