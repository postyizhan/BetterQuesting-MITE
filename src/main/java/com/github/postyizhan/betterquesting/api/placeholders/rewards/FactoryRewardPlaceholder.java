package com.github.postyizhan.betterquesting.api.placeholders.rewards;

import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTTagCompound;

public final class FactoryRewardPlaceholder implements IFactoryData<RewardPlaceholder, NBTTagCompound> {
    public static final FactoryRewardPlaceholder INSTANCE = new FactoryRewardPlaceholder();
    private static final ResourceKey ID = ResourceKey.parse("betterquesting:placeholder");

    private FactoryRewardPlaceholder() {
    }

    @Override
    public ResourceKey getRegistryName() {
        return ID;
    }

    @Override
    public RewardPlaceholder createNew() {
        return new RewardPlaceholder();
    }

    @Override
    public RewardPlaceholder loadFromData(NBTTagCompound nbt) {
        RewardPlaceholder reward = createNew();
        reward.readFromNBT(nbt);
        return reward;
    }
}
