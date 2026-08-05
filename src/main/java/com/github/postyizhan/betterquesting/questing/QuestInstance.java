package com.github.postyizhan.betterquesting.questing;

import com.github.postyizhan.betterquesting.api.enums.EnumLogic;
import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.IPropertyType;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.IQuest;
import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.storage.IDatabaseNBT;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import com.github.postyizhan.betterquesting.platform.api.DirtyPlayerSink;
import com.github.postyizhan.betterquesting.questing.rewards.RewardStorage;
import com.github.postyizhan.betterquesting.questing.tasks.TaskStorage;
import com.github.postyizhan.betterquesting.storage.PropertyContainer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class QuestInstance implements IQuest {
    private static final Logger LOGGER = LogManager.getLogger("BetterQuesting/QuestInstance");

    private final TaskStorage tasks = new TaskStorage();
    private final RewardStorage rewards = new RewardStorage();
    private final Map<UUID, NBTTagCompound> completeUsers = new HashMap<>();
    private Set<UUID> preRequisites = new HashSet<>();
    private final Map<UUID, RequirementType> prereqTypes = new HashMap<>();
    private final PropertyContainer qInfo = new PropertyContainer();
    private final DirtyPlayerSink dirtyPlayers;

    public QuestInstance() {
        this(DirtyPlayerSink.NO_OP);
    }

    public QuestInstance(DirtyPlayerSink dirtyPlayers) {
        this.dirtyPlayers = dirtyPlayers == null ? DirtyPlayerSink.NO_OP : dirtyPlayers;
        setupProps();
    }

    private void setupProps() {
        setupValue(NativeProps.NAME, "New Quest");
        setupValue(NativeProps.DESC, "No Description");
        setupValue(NativeProps.SOUND_COMPLETE);
        setupValue(NativeProps.SOUND_UPDATE);
        setupValue(NativeProps.COMPLETION_PARTICLE);
        setupValue(NativeProps.COMPLETION_ANIMATION);
        setupValue(NativeProps.PARTICLE_COUNT);
        setupValue(NativeProps.NOTIFICATION_STYLE);
        setupValue(NativeProps.NOTIFICATION_SHOW_ICON);
        setupValue(NativeProps.NOTIFICATION_TITLE);
        setupValue(NativeProps.NOTIFICATION_SUBTITLE);
        setupValue(NativeProps.NOTIFICATION_DURATION);
        setupValue(NativeProps.NOTIFICATION_FADE_IN);
        setupValue(NativeProps.NOTIFICATION_FADE_OUT);
        setupValue(NativeProps.NOTIFICATION_TITLE_SCALE);
        setupValue(NativeProps.NOTIFICATION_SUBTITLE_SCALE);
        setupValue(NativeProps.NOTIFICATION_ICON_SCALE);
        setupValue(NativeProps.NOTIFICATION_ICON_OFFSET_Y);
        setupValue(NativeProps.NOTIFICATION_POS_X);
        setupValue(NativeProps.NOTIFICATION_POS_Y);
        setupValue(NativeProps.NOTIFICATION_EFFECT);
        setupValue(NativeProps.LOGIC_QUEST, EnumLogic.AND);
        setupValue(NativeProps.LOGIC_TASK, EnumLogic.AND);
        setupValue(NativeProps.REPEAT_TIME, -1);
        setupValue(NativeProps.REPEAT_REL, true);
        setupValue(NativeProps.LOCKED_PROGRESS, false);
        setupValue(NativeProps.AUTO_CLAIM, false);
        setupValue(NativeProps.SILENT, false);
        setupValue(NativeProps.MAIN, false);
        setupValue(NativeProps.GLOBAL_SHARE, false);
        setupValue(NativeProps.SIMULTANEOUS, false);
        setupValue(NativeProps.COUNT_AS_QUEST, true);
        setupValue(NativeProps.VISIBILITY, EnumQuestVisibility.NORMAL);
    }

    private <T> void setupValue(IPropertyType<T> property) {
        setupValue(property, property.getDefault());
    }

    private <T> void setupValue(IPropertyType<T> property, T defaultValue) {
        qInfo.setProperty(property, qInfo.getProperty(property, defaultValue));
    }

    @Override
    public boolean hasClaimed(UUID uuid) {
        if (rewards.size() <= 0) return true;
        synchronized (completeUsers) {
            if (qInfo.getProperty(NativeProps.GLOBAL) && !qInfo.getProperty(NativeProps.GLOBAL_SHARE)) {
                return completeUsers.values().stream().anyMatch(entry -> entry != null && entry.getBoolean("claimed"));
            }
            NBTTagCompound entry = getCompletionInfo(uuid);
            return entry != null && entry.getBoolean("claimed");
        }
    }

    @Override
    public void setComplete(UUID uuid, long timestamp) {
        if (uuid == null) return;
        synchronized (completeUsers) {
            NBTTagCompound entry = getCompletionInfo(uuid);
            if (entry == null) {
                entry = new NBTTagCompound();
                completeUsers.put(uuid, entry);
            }
            entry.setBoolean("claimed", false);
            entry.setLong("timestamp", timestamp);
            dirtyPlayers.markDirty(uuid);
        }
        for (DBEntry<ITask> entry : tasks.getEntries()) {
            ITask task = entry.getValue();
            if (task != null && task.ignored(uuid)) task.setComplete(uuid);
        }
    }

    @Override
    public boolean isComplete(UUID uuid) {
        return qInfo.getProperty(NativeProps.GLOBAL) ? !completeUsers.isEmpty() : getCompletionInfo(uuid) != null;
    }

    @Override
    public NBTTagCompound getCompletionInfo(UUID uuid) {
        synchronized (completeUsers) {
            return completeUsers.get(uuid);
        }
    }

    @Override
    public void setCompletionInfo(UUID uuid, NBTTagCompound nbt) {
        if (uuid == null) return;
        synchronized (completeUsers) {
            if (nbt == null) completeUsers.remove(uuid); else completeUsers.put(uuid, nbt);
            dirtyPlayers.markDirty(uuid);
        }
    }

    @Override
    public void resetUser(UUID uuid, boolean fullReset) {
        synchronized (completeUsers) {
            Set<UUID> dirty = new HashSet<>();
            if (uuid == null) dirty.addAll(completeUsers.keySet()); else dirty.add(uuid);
            if (fullReset) {
                if (uuid == null) completeUsers.clear(); else completeUsers.remove(uuid);
            } else if (uuid == null) {
                completeUsers.values().forEach(value -> {
                    value.setBoolean("claimed", false);
                    value.setLong("timestamp", 0);
                });
            } else {
                NBTTagCompound entry = getCompletionInfo(uuid);
                if (entry != null) {
                    entry.setBoolean("claimed", false);
                    entry.setLong("timestamp", 0);
                }
            }
            dirtyPlayers.markDirty(dirty);
            tasks.getEntries().forEach(entry -> entry.getValue().resetUser(uuid));
        }
    }

    @Override
    public IDatabaseNBT<ITask, NBTTagList, NBTTagList> getTasks() {
        return tasks;
    }

    @Override
    public IDatabaseNBT<IReward, NBTTagList, NBTTagList> getRewards() {
        return rewards;
    }

    @Override
    public Set<UUID> getRequirements() {
        return preRequisites;
    }

    @Override
    public void setRequirements(Iterable<UUID> requirements) {
        preRequisites.clear();
        requirements.forEach(preRequisites::add);
        prereqTypes.keySet().removeIf(key -> !preRequisites.contains(key));
    }

    @Override
    public RequirementType getRequirementType(UUID requirement) {
        return prereqTypes.getOrDefault(requirement, RequirementType.NORMAL);
    }

    @Override
    public void setRequirementType(UUID requirement, RequirementType type) {
        if (type == RequirementType.NORMAL) prereqTypes.remove(requirement); else prereqTypes.put(requirement, type);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("properties", qInfo.writeToNBT(new NBTTagCompound()));
        nbt.setTag("tasks", tasks.writeToNBT(new NBTTagList(), null));
        nbt.setTag("rewards", rewards.writeToNBT(new NBTTagList(), null));
        NBTTagList requirements = new NBTTagList();
        for (UUID requirement : preRequisites) {
            NBTTagCompound tag = UuidValueType.QUEST.writeId(requirement);
            if (prereqTypes.containsKey(requirement)) tag.setByte("type", prereqTypes.get(requirement).id());
            requirements.appendTag(tag);
        }
        nbt.setTag("preRequisites", requirements);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        qInfo.readFromNBT(nbt.getCompoundTag("properties"));
        qInfo.setProperty(NativeProps.COUNT_AS_QUEST, qInfo.getProperty(NativeProps.COUNT_AS_QUEST));
        tasks.readFromNBT(NbtCompat.getListOrEmpty(nbt, "tasks"), false);
        rewards.readFromNBT(NbtCompat.getListOrEmpty(nbt, "rewards"), false);
        // Deliberate deviation: upstream never resets prereqTypes here, so re-reading NBT into an existing
        // instance leaves a stale type for a requirement whose "type" field is now absent, silently reporting
        // HIDDEN/IMPLICIT where the saved data means NORMAL. Clearing first makes the read a full replace.
        prereqTypes.clear();
        Map<Integer, UUID> legacyIndex = new HashMap<>();
        if (NbtCompat.getTagId(nbt, "preRequisites") == 9) {
            preRequisites = new HashSet<>();
            NBTTagList list = NbtCompat.getListOrEmpty(nbt, "preRequisites");
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound tag = NbtCompat.getCompoundAt(list, i);
                if (tag == null) continue;
                Optional<UUID> requirement = UuidValueType.QUEST.tryReadId(tag);
                if (requirement.isEmpty()) continue;
                preRequisites.add(requirement.get());
                if (NbtCompat.isNumeric(tag, "type")) {
                    setRequirementType(requirement.get(), RequirementType.from(tag.getByte("type")));
                }
            }
        } else if (NbtCompat.getTagId(nbt, "preRequisites") == 11) {
            preRequisites = new HashSet<>();
            int[] ids = nbt.getIntArray("preRequisites");
            for (int i = 0; i < ids.length; i++) {
                UUID requirement = UuidConverter.convertLegacyId(ids[i]);
                preRequisites.add(requirement);
                legacyIndex.put(i, requirement);
            }
        }
        if (NbtCompat.getTagId(nbt, "preRequisiteTypes") == 7) {
            byte[] types = nbt.getByteArray("preRequisiteTypes");
            for (int i = 0; i < types.length; i++) {
                UUID requirement = legacyIndex.get(i);
                if (requirement != null) setRequirementType(requirement, RequirementType.from(types[i]));
            }
        }
        setupProps();
    }

    @Override
    public NBTTagCompound writeProgressToNBT(NBTTagCompound nbt, List<UUID> users) {
        synchronized (completeUsers) {
            NBTTagList completed = new NBTTagList();
            for (Map.Entry<UUID, NBTTagCompound> entry : completeUsers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                if (users != null && !users.contains(entry.getKey())) continue;
                NBTTagCompound tag = (NBTTagCompound) entry.getValue().copy();
                tag.setString("uuid", entry.getKey().toString());
                completed.appendTag(tag);
            }
            nbt.setTag("completed", completed);
            nbt.setTag("tasks", tasks.writeProgressToNBT(new NBTTagList(), users));
            return nbt;
        }
    }

    @Override
    public void readProgressFromNBT(NBTTagCompound nbt, boolean merge) {
        synchronized (completeUsers) {
            if (!merge) completeUsers.clear();
            NBTTagList completed = NbtCompat.getListOrEmpty(nbt, "completed");
            for (int i = 0; i < completed.tagCount(); i++) {
                NBTTagCompound item = NbtCompat.getCompoundAt(completed, i);
                if (item == null) continue;
                NBTTagCompound entry = (NBTTagCompound) item.copy();
                try {
                    completeUsers.put(UUID.fromString(entry.getString("uuid")), entry);
                } catch (Exception exception) {
                    // Matches upstream: one corrupt record must not abort the whole progress file. The broad
                    // catch is deliberate because the failure modes are untrusted save data, not logic errors.
                    LOGGER.error("Unable to load UUID for quest progress record {}", i, exception);
                }
            }
            tasks.readProgressFromNBT(NbtCompat.getListOrEmpty(nbt, "tasks"), merge);
        }
    }

    @Override
    public void setClaimed(UUID uuid, long timestamp) {
        if (uuid == null) return;
        synchronized (completeUsers) {
            NBTTagCompound entry = getCompletionInfo(uuid);
            if (entry == null) {
                entry = new NBTTagCompound();
                completeUsers.put(uuid, entry);
            }
            entry.setBoolean("claimed", true);
            entry.setLong("timestamp", timestamp);
            dirtyPlayers.markDirty(uuid);
        }
    }

    public void getUsersWithCompletionData(Set<UUID> target) {
        synchronized (completeUsers) {
            target.addAll(completeUsers.keySet());
        }
    }

    @Override public <T> T getProperty(IPropertyType<T> property) { return qInfo.getProperty(property); }
    @Override public <T> T getProperty(IPropertyType<T> property, T defaultValue) { return qInfo.getProperty(property, defaultValue); }
    @Override public boolean hasProperty(IPropertyType<?> property) { return qInfo.hasProperty(property); }
    @Override public <T> void setProperty(IPropertyType<T> property, T value) { qInfo.setProperty(property, value); }
    @Override public void removeProperty(IPropertyType<?> property) { qInfo.removeProperty(property); }
    @Override public void removeAllProps() { qInfo.removeAllProps(); }
}
