package com.github.postyizhan.betterquesting.api.questing;

import com.github.postyizhan.betterquesting.api.storage.INBTPartial;
import com.github.postyizhan.betterquesting.api.storage.INBTProgress;
import com.github.postyizhan.betterquesting.api.storage.IUuidDatabase;
import java.util.UUID;
import net.minecraft.NBTTagList;

public interface IQuestDatabase extends IUuidDatabase<IQuest>, INBTPartial<NBTTagList, UUID>, INBTProgress<NBTTagList> {
    IQuest createNew(UUID questId);
}
