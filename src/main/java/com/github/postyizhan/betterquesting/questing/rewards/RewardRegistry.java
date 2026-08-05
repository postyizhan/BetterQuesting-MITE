package com.github.postyizhan.betterquesting.questing.rewards;

import com.github.postyizhan.betterquesting.api.placeholders.rewards.FactoryRewardPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.registry.IRegistry;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.NBTTagCompound;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RewardRegistry implements IRegistry<IFactoryData<IReward, NBTTagCompound>, IReward> {
    public static final RewardRegistry INSTANCE = new RewardRegistry();
    private static final Logger LOGGER = LogManager.getLogger("BetterQuesting/RewardRegistry");
    private final Map<ResourceKey, IFactoryData<IReward, NBTTagCompound>> factories = new HashMap<>();

    private RewardRegistry() {
    }

    @Override
    public void register(IFactoryData<IReward, NBTTagCompound> factory) {
        if (factory == null) throw new NullPointerException("Tried to register null reward");
        if (factory.getRegistryName() == null) throw new IllegalArgumentException("Reward factory has a null name");
        if (factories.containsKey(factory.getRegistryName()) || factories.containsValue(factory)) {
            throw new IllegalArgumentException("Cannot register duplicate reward type: " + factory.getRegistryName());
        }
        factories.put(factory.getRegistryName(), factory);
    }

    @Override
    public IFactoryData<IReward, NBTTagCompound> getFactory(ResourceKey id) {
        return factories.get(id);
    }

    @Override
    public List<IFactoryData<IReward, NBTTagCompound>> getAll() {
        return new ArrayList<>(factories.values());
    }

    @Override
    public IReward createNew(ResourceKey id) {
        try {
            IFactoryData<? extends IReward, NBTTagCompound> factory =
                FactoryRewardPlaceholder.INSTANCE.getRegistryName().equals(id) ? FactoryRewardPlaceholder.INSTANCE : getFactory(id);
            if (factory == null) {
                LOGGER.error("Tried to load missing reward type '{}'. Are you missing an expansion pack?", id);
                return null;
            }
            return factory.createNew();
        } catch (Exception exception) {
            LOGGER.error("Unable to instantiate reward: " + id, exception);
            return null;
        }
    }
}
