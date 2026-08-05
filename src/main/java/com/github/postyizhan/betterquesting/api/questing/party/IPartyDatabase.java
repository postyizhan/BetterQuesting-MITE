package com.github.postyizhan.betterquesting.api.questing.party;

import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.storage.IDatabase;
import com.github.postyizhan.betterquesting.api.storage.INBTPartial;
import java.util.UUID;
import net.minecraft.NBTTagList;

public interface IPartyDatabase extends IDatabase<IParty>, INBTPartial<NBTTagList, Integer> {
    IParty createNew(int id);

    DBEntry<IParty> getParty(UUID uuid);
}
