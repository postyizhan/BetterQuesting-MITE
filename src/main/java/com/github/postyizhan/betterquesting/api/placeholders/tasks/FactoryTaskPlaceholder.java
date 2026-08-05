package com.github.postyizhan.betterquesting.api.placeholders.tasks;

import com.github.postyizhan.betterquesting.api.registry.IFactoryData;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import net.minecraft.NBTTagCompound;

public final class FactoryTaskPlaceholder implements IFactoryData<TaskPlaceholder, NBTTagCompound> {
    public static final FactoryTaskPlaceholder INSTANCE = new FactoryTaskPlaceholder();
    private static final ResourceKey ID = ResourceKey.parse("betterquesting:placeholder");

    private FactoryTaskPlaceholder() {
    }

    @Override
    public ResourceKey getRegistryName() {
        return ID;
    }

    @Override
    public TaskPlaceholder createNew() {
        return new TaskPlaceholder();
    }

    @Override
    public TaskPlaceholder loadFromData(NBTTagCompound nbt) {
        TaskPlaceholder task = createNew();
        task.readFromNBT(nbt);
        return task;
    }
}
