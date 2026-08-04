package com.github.postyizhan.betterquesting.api.questing;

import com.github.postyizhan.betterquesting.api.storage.INBTPartial;
import com.github.postyizhan.betterquesting.api.storage.IUuidDatabase;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.NBTTagList;

public interface IQuestLineDatabase extends IUuidDatabase<IQuestLine>, INBTPartial<NBTTagList, UUID> {
    IQuestLine createNew(UUID lineId);

    void removeQuest(UUID questId);

    int getOrderIndex(UUID lineId);

    void setOrderIndex(UUID lineId, int index);

    List<Map.Entry<UUID, IQuestLine>> getOrderedEntries();

    void setOrderedEntries(Collection<Map.Entry<UUID, IQuestLine>> entries);

    @Override
    default Stream<Map.Entry<UUID, IQuestLine>> orderedEntries() {
        return getOrderedEntries().stream();
    }
}
