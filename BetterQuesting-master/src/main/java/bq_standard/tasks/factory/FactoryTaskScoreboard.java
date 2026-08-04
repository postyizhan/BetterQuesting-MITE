package bq_standard.tasks.factory;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import betterquesting.api.questing.tasks.ITask;
import betterquesting.api2.registry.IFactoryData;
import bq_standard.core.BQ_Standard;
import bq_standard.tasks.TaskScoreboard;

public class FactoryTaskScoreboard implements IFactoryData<ITask, NBTTagCompound> {

    public static final FactoryTaskScoreboard INSTANCE = new FactoryTaskScoreboard();

    @Override
    public ResourceLocation getRegistryName() {
        return new ResourceLocation(BQ_Standard.MODID + ":scoreboard");
    }

    @Override
    public TaskScoreboard createNew() {
        return new TaskScoreboard();
    }

    @Override
    public TaskScoreboard loadFromData(NBTTagCompound json) {
        TaskScoreboard task = new TaskScoreboard();
        task.readFromNBT(json);
        return task;
    }
}
