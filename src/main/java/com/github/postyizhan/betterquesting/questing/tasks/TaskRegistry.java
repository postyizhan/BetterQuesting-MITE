package com.github.postyizhan.betterquesting.questing.tasks;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.FactoryTaskPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
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

public final class TaskRegistry implements IRegistry<IFactoryData<ITask, NBTTagCompound>, ITask> {
    public static final TaskRegistry INSTANCE = new TaskRegistry();
    private static final Logger LOGGER = LogManager.getLogger("BetterQuesting/TaskRegistry");
    private final Map<ResourceKey, IFactoryData<ITask, NBTTagCompound>> factories = new HashMap<>();

    private TaskRegistry() {
    }

    @Override
    public void register(IFactoryData<ITask, NBTTagCompound> factory) {
        if (factory == null) throw new NullPointerException("Tried to register null task");
        if (factory.getRegistryName() == null) throw new IllegalArgumentException("Task factory has a null name");
        if (factories.containsKey(factory.getRegistryName()) || factories.containsValue(factory)) {
            throw new IllegalArgumentException("Cannot register duplicate task type: " + factory.getRegistryName());
        }
        factories.put(factory.getRegistryName(), factory);
    }

    @Override
    public IFactoryData<ITask, NBTTagCompound> getFactory(ResourceKey id) {
        return factories.get(id);
    }

    @Override
    public List<IFactoryData<ITask, NBTTagCompound>> getAll() {
        return new ArrayList<>(factories.values());
    }

    @Override
    public ITask createNew(ResourceKey id) {
        try {
            IFactoryData<? extends ITask, NBTTagCompound> factory =
                FactoryTaskPlaceholder.INSTANCE.getRegistryName().equals(id) ? FactoryTaskPlaceholder.INSTANCE : getFactory(id);
            if (factory == null) {
                LOGGER.error("Tried to load missing task type '{}'. Are you missing an expansion pack?", id);
                return null;
            }
            return factory.createNew();
        } catch (Exception exception) {
            LOGGER.error("Unable to instantiate task: " + id, exception);
            return null;
        }
    }
}
