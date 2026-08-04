package com.github.postyizhan.betterquesting.api.questing;

import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.storage.INBTPartial;
import com.github.postyizhan.betterquesting.api.storage.IUuidDatabase;
import java.util.Map;
import java.util.UUID;
import net.minecraft.NBTTagCompound;

public interface IQuestLine
    extends IUuidDatabase<IQuestLineEntry>, INBTPartial<NBTTagCompound, Integer>, IPropertyContainer {
    IQuestLineEntry createNew(UUID uuid);

    String getUnlocalisedName();

    String getUnlocalisedDescription();

    Map.Entry<UUID, IQuestLineEntry> getEntryAt(int x, int y);

    NBTTagCompound writeToNBT(NBTTagCompound nbt, boolean skipQuests);
}
