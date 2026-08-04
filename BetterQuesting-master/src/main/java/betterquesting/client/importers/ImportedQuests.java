package betterquesting.client.importers;

import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagList;

import betterquesting.questing.QuestDatabase;

public class ImportedQuests extends QuestDatabase {

    @Override
    public NBTTagList writeProgressToNBT(NBTTagList nbt, List<UUID> users) {
        return nbt;
    }

    @Override
    public void readProgressFromNBT(NBTTagList nbt, boolean merge) {}
}
