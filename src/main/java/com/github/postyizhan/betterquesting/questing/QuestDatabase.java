package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.questing.IQuestDatabase;
import com.github.postyizhan.betterquesting.api.storage.UuidDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public class QuestDatabase extends UuidDatabase<IQuest> implements IQuestDatabase {
    public static final QuestDatabase INSTANCE = new QuestDatabase();

    @Override
    public synchronized IQuest createNew(UUID questId) {
        IQuest quest = new QuestInstance();
        put(questId, quest);
        return quest;
    }

    @Override
    public IQuest remove(Object key) {
        if (!(key instanceof UUID questId)) return null;
        IQuest removed = super.remove(questId);
        if (removed != null) values().forEach(quest -> removeRequirement(quest, questId));
        return removed;
    }

    @Override
    public UUID removeValue(IQuest value) {
        UUID questId = super.removeValue(value);
        if (questId != null) values().forEach(quest -> removeRequirement(quest, questId));
        return questId;
    }

    private void removeRequirement(IQuest quest, UUID questId) {
        if (quest != null) quest.getRequirements().remove(questId);
    }

    @Override
    public synchronized NBTTagList writeToNBT(NBTTagList nbt, List<UUID> subset) {
        orderedEntries().forEach(entry -> {
            if (subset != null && !subset.contains(entry.getKey())) return;
            if (entry.getValue() == null) return;
            NBTTagCompound tag = entry.getValue().writeToNBT(new NBTTagCompound());
            UuidValueType.QUEST.writeId(entry.getKey(), tag);
            nbt.appendTag(tag);
        });
        return nbt;
    }

    @Override
    public synchronized void readFromNBT(NBTTagList nbt, boolean merge) {
        if (!merge) clear();
        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound tag = NbtCompat.getCompoundAt(nbt, i);
            if (tag == null) continue;
            Optional<UUID> current = UuidValueType.QUEST.tryReadId(tag);
            UUID questId = current.orElseGet(() -> NbtCompat.isNumeric(tag, "questID")
                ? UuidConverter.convertLegacyId(tag.getInteger("questID")) : null);
            if (questId == null) continue;
            IQuest quest = get(questId);
            if (quest == null) quest = createNew(questId);
            quest.readFromNBT(tag);
        }
    }

    @Override
    public synchronized NBTTagList writeProgressToNBT(NBTTagList nbt, List<UUID> users) {
        for (Map.Entry<UUID, IQuest> entry : entrySet()) {
            if (entry.getValue() == null) continue;
            NBTTagCompound tag = entry.getValue().writeProgressToNBT(new NBTTagCompound(), users);
            UuidValueType.QUEST.writeId(entry.getKey(), tag);
            nbt.appendTag(tag);
        }
        return nbt;
    }

    @Override
    public synchronized void readProgressFromNBT(NBTTagList nbt, boolean merge) {
        for (int i = 0; i < nbt.tagCount(); i++) {
            NBTTagCompound tag = NbtCompat.getCompoundAt(nbt, i);
            if (tag == null) continue;
            Optional<UUID> current = UuidValueType.QUEST.tryReadId(tag);
            UUID questId = current.orElseGet(() -> NbtCompat.isNumeric(tag, "questID")
                ? UuidConverter.convertLegacyId(tag.getInteger("questID")) : null);
            if (questId == null) continue;
            IQuest quest = get(questId);
            if (quest != null) quest.readProgressFromNBT(tag, merge);
        }
    }
}
