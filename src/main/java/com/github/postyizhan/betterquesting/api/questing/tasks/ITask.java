package com.github.postyizhan.betterquesting.api.questing.tasks;

import com.github.postyizhan.betterquesting.api.storage.INBTProgress;
import com.github.postyizhan.betterquesting.api.storage.INBTSaveLoad;
import com.github.postyizhan.betterquesting.api.util.ResourceKey;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

/**
 * Player detection is deferred with ParticipantInfo to stages 6/7. getTaskGui and getTaskEditor are deferred with
 * the client GUI layer to stage 5.
 */
public interface ITask extends INBTSaveLoad<NBTTagCompound>, INBTProgress<NBTTagCompound> {
    String getUnlocalisedName();

    ResourceKey getFactoryID();

    boolean isComplete(UUID uuid);

    void setComplete(UUID uuid);

    void resetUser(UUID uuid);

    default boolean ignored(UUID uuid) {
        return false;
    }

    default List<String> getTextsForSearch() {
        return null;
    }
}
