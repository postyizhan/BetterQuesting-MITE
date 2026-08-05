package com.github.postyizhan.betterquesting.questing.rewards;

import com.github.postyizhan.betterquesting.api.placeholders.rewards.RewardPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.storage.IDatabaseNBT;
import com.github.postyizhan.betterquesting.api.storage.SimpleDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public class RewardStorage extends SimpleDatabase<IReward> implements IDatabaseNBT<IReward, NBTTagList, NBTTagList> {
    @Override
    public NBTTagList writeToNBT(NBTTagList nbt, List<Integer> subset) {
        for (DBEntry<IReward> entry : getEntries()) {
            if (subset != null && !subset.contains(entry.getID())) continue;
            NBTTagCompound tag = entry.getValue().writeToNBT(new NBTTagCompound());
            tag.setString("rewardID", entry.getValue().getFactoryID().toString());
            tag.setInteger("index", entry.getID());
            nbt.appendTag(tag);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) reset();
        List<IReward> unassigned = new ArrayList<>();
        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound tag = NbtCompat.getCompoundAt(nbt, i);
            if (tag == null) continue;
            int index = NbtCompat.isNumeric(tag, "index") ? tag.getInteger("index") : -1;
            IReward reward = RewardRegistry.INSTANCE.createNew(ResourceKey.parse(tag.getString("rewardID")));
            if (reward instanceof RewardPlaceholder) {
                NBTTagCompound original = tag.getCompoundTag("orig_data");
                IReward restored = RewardRegistry.INSTANCE.createNew(ResourceKey.parse(original.getString("rewardID")));
                if (restored != null) {
                    tag = original;
                    reward = restored;
                }
            }
            if (reward != null) reward.readFromNBT(tag);
            else {
                RewardPlaceholder placeholder = new RewardPlaceholder();
                placeholder.setRewardConfigData(tag);
                reward = placeholder;
            }
            if (index >= 0) add(index, reward); else unassigned.add(reward);
        }
        for (IReward reward : unassigned) add(nextID(), reward);
    }

    @Override
    public NBTTagList writeProgressToNBT(NBTTagList nbt, List<UUID> users) {
        return nbt;
    }

    @Override
    public void readProgressFromNBT(NBTTagList nbt, boolean merge) {
    }
}
