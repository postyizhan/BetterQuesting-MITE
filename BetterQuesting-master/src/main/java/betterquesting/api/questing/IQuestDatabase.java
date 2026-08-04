package betterquesting.api.questing;

import java.util.UUID;

import net.minecraft.nbt.NBTTagList;

import betterquesting.api2.storage.INBTPartial;
import betterquesting.api2.storage.INBTProgress;
import betterquesting.api2.storage.IUuidDatabase;

public interface IQuestDatabase extends IUuidDatabase<IQuest>, INBTPartial<NBTTagList, UUID>, INBTProgress<NBTTagList> {

    IQuest createNew(UUID uuid);
}
