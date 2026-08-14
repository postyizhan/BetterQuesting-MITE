package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.questing.IQuestDatabase;
import com.github.postyizhan.betterquesting.api.storage.UuidDatabase;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.platform.api.DirtyPlayerSink;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public class QuestDatabase extends UuidDatabase<IQuest> implements IQuestDatabase {
    public static final QuestDatabase INSTANCE = new QuestDatabase();
    private DirtyPlayerSink dirtyPlayers;

    public QuestDatabase() {
        this(DirtyPlayerSink.NO_OP);
    }

    public QuestDatabase(DirtyPlayerSink dirtyPlayers) {
        this.dirtyPlayers = dirtyPlayers == null ? DirtyPlayerSink.NO_OP : dirtyPlayers;
    }

    public synchronized void setDirtyPlayerSink(DirtyPlayerSink dirtyPlayers) {
        this.dirtyPlayers = dirtyPlayers == null ? DirtyPlayerSink.NO_OP : dirtyPlayers;
        values().forEach(quest -> {
            if (quest instanceof QuestInstance instance) instance.setDirtyPlayerSink(this.dirtyPlayers);
        });
    }

    @Override
    public synchronized IQuest createNew(UUID questId) {
        IQuest quest = new QuestInstance(dirtyPlayers);
        put(questId, quest);
        return quest;
    }

    @Override
    public IQuest remove(Object key) {
        if (!(key instanceof UUID questId)) return null;
        Removal removal;
        synchronized (this) {
            removal = removeLocked(questId);
        }
        removal.dirtyPlayers().markDirty(removal.affectedPlayers());
        return removal.quest();
    }

    @Override
    public UUID removeValue(IQuest value) {
        UUID questId;
        Removal removal;
        synchronized (this) {
            questId = lookupKey(value);
            if (questId == null || !containsKey(questId)) return null;
            removal = removeLocked(questId);
        }
        removal.dirtyPlayers().markDirty(removal.affectedPlayers());
        return questId;
    }

    private Removal removeLocked(UUID questId) {
        Set<UUID> affectedPlayers = completionUsers(get(questId));
        IQuest removed = super.remove(questId);
        if (removed != null) values().forEach(quest -> removeRequirement(quest, questId));
        return new Removal(removed, affectedPlayers, dirtyPlayers);
    }

    private Set<UUID> completionUsers(IQuest quest) {
        Set<UUID> players = new HashSet<>();
        if (quest instanceof QuestInstance instance) instance.getUsersWithCompletionData(players);
        return players;
    }

    private void removeRequirement(IQuest quest, UUID questId) {
        if (quest != null) quest.getRequirements().remove(questId);
    }

    private record Removal(IQuest quest, Set<UUID> affectedPlayers, DirtyPlayerSink dirtyPlayers) {
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
            UUID questId = readQuestId(tag);
            if (questId == null) continue;
            IQuest quest = get(questId);
            if (quest == null) quest = createNew(questId);
            quest.readFromNBT(tag);
        }
    }

    @Override
    public synchronized NBTTagList writeProgressToNBT(NBTTagList nbt, List<UUID> users) {
        for (Map.Entry<UUID, IQuest> entry : orderedEntries().toList()) {
            if (entry.getValue() == null) continue;
            NBTTagCompound tag = entry.getValue().writeProgressToNBT(new NBTTagCompound(), users);
            if (NbtCompat.getListOrEmpty(tag, "completed").tagCount() == 0
                && NbtCompat.getListOrEmpty(tag, "tasks").tagCount() == 0) continue;
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
            UUID questId = readQuestId(tag);
            if (questId == null) continue;
            IQuest quest = get(questId);
            if (quest != null) quest.readProgressFromNBT(tag, merge);
        }
    }

    private static UUID readQuestId(NBTTagCompound tag) {
        Optional<UUID> current = UuidValueType.QUEST.tryReadId(tag);
        if (current.isPresent()) return current.get();
        if (NbtCompat.getTagId(tag, "questID") != 3) return null;
        try {
            return UuidConverter.convertLegacyId(tag.getInteger("questID"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
