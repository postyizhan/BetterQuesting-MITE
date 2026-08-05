package com.github.postyizhan.betterquesting.questing.tasks;

import com.github.postyizhan.betterquesting.api.placeholders.tasks.FactoryTaskPlaceholder;
import com.github.postyizhan.betterquesting.api.placeholders.tasks.TaskPlaceholder;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
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

public class TaskStorage extends SimpleDatabase<ITask> implements IDatabaseNBT<ITask, NBTTagList, NBTTagList> {
    @Override
    public NBTTagList writeToNBT(NBTTagList nbt, List<Integer> subset) {
        for (DBEntry<ITask> entry : getEntries()) {
            if (subset != null && !subset.contains(entry.getID())) continue;
            NBTTagCompound tag = entry.getValue().writeToNBT(new NBTTagCompound());
            tag.setString("taskID", entry.getValue().getFactoryID().toString());
            tag.setInteger("index", entry.getID());
            nbt.appendTag(tag);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) reset();
        List<ITask> unassigned = new ArrayList<>();
        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound tag = NbtCompat.getCompoundAt(nbt, i);
            if (tag == null) continue;
            int index = NbtCompat.isNumeric(tag, "index") ? tag.getInteger("index") : -1;
            ITask task = TaskRegistry.INSTANCE.createNew(ResourceKey.parse(tag.getString("taskID")));
            if (task instanceof TaskPlaceholder) {
                NBTTagCompound original = tag.getCompoundTag("orig_data");
                ITask restored = TaskRegistry.INSTANCE.createNew(ResourceKey.parse(original.getString("taskID")));
                if (restored != null) {
                    tag = original;
                    task = restored;
                }
            }
            if (task != null) task.readFromNBT(tag);
            else {
                TaskPlaceholder placeholder = new TaskPlaceholder();
                placeholder.setTaskConfigData(tag);
                task = placeholder;
            }
            if (index >= 0) add(index, task); else unassigned.add(task);
        }
        for (ITask task : unassigned) add(nextID(), task);
    }

    @Override
    public NBTTagList writeProgressToNBT(NBTTagList nbt, List<UUID> users) {
        for (DBEntry<ITask> entry : getEntries()) {
            NBTTagCompound tag = entry.getValue().writeProgressToNBT(new NBTTagCompound(), users);
            tag.setString("taskID", entry.getValue().getFactoryID().toString());
            tag.setInteger("index", entry.getID());
            nbt.appendTag(tag);
        }
        return nbt;
    }

    @Override
    public void readProgressFromNBT(NBTTagList nbt, boolean merge) {
        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound tag = NbtCompat.getCompoundAt(nbt, i);
            if (tag == null) continue;
            int index = NbtCompat.isNumeric(tag, "index") ? tag.getInteger("index") : -1;
            ResourceKey id = ResourceKey.parse(tag.getString("taskID"));
            ITask task = getValue(index);
            if (task instanceof TaskPlaceholder placeholder) {
                if (!task.getFactoryID().equals(id)) placeholder.setTaskProgressData(tag);
                else task.readProgressFromNBT(tag, merge);
            } else if (task != null) {
                if (task.getFactoryID().equals(id)) task.readProgressFromNBT(tag, merge);
                else if (FactoryTaskPlaceholder.INSTANCE.getRegistryName().equals(id)) {
                    task.readProgressFromNBT(tag.getCompoundTag("orig_prog"), merge);
                }
            }
        }
    }
}
