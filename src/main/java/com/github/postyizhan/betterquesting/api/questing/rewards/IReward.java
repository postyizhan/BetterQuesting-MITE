package com.github.postyizhan.betterquesting.api.questing.rewards;

import com.github.postyizhan.betterquesting.api.storage.INBTSaveLoad;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTTagCompound;

/**
 * canClaim and claimReward are deferred with player identity/participant handling to stages 6/7. getRewardGui and
 * getRewardEditor are deferred with the client GUI layer to stage 5.
 */
public interface IReward extends INBTSaveLoad<NBTTagCompound> {
    String getUnlocalisedName();

    ResourceKey getFactoryID();
}
