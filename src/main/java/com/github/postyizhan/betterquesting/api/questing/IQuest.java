package com.github.postyizhan.betterquesting.api.questing;

import com.github.postyizhan.betterquesting.api.properties.IPropertyContainer;
import com.github.postyizhan.betterquesting.api.questing.rewards.IReward;
import com.github.postyizhan.betterquesting.api.questing.tasks.ITask;
import com.github.postyizhan.betterquesting.api.storage.IDatabaseNBT;
import com.github.postyizhan.betterquesting.api.storage.INBTProgress;
import com.github.postyizhan.betterquesting.api.storage.INBTSaveLoad;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

public interface IQuest extends INBTSaveLoad<NBTTagCompound>, INBTProgress<NBTTagCompound>, IPropertyContainer {
    /*
     * TODO stages 6/7: getState, update, detect, canSubmit, isUnlocked, isUnlockable, canClaim,
     * canClaimBasically and claimReward require QuestingAPI, QuestCache, ParticipantInfo, PartyManager and
     * EnumQuestState evaluation. canClaim/claimReward also carry upstream reverse dependencies on RewardChoice and
     * QBConfig. Player-facing signatures are intentionally absent until those layers exist.
     */
    NBTTagCompound getCompletionInfo(UUID uuid);

    void setCompletionInfo(UUID uuid, NBTTagCompound nbt);

    boolean isComplete(UUID uuid);

    void setComplete(UUID uuid, long timestamp);

    boolean hasClaimed(UUID uuid);

    void setClaimed(UUID uuid, long timestamp);

    void resetUser(UUID uuid, boolean fullReset);

    IDatabaseNBT<ITask, NBTTagList, NBTTagList> getTasks();

    IDatabaseNBT<IReward, NBTTagList, NBTTagList> getRewards();

    Set<UUID> getRequirements();

    void setRequirements(Iterable<UUID> requirements);

    RequirementType getRequirementType(UUID requirement);

    void setRequirementType(UUID requirement, RequirementType type);

    enum RequirementType {
        NORMAL,
        IMPLICIT,
        HIDDEN;

        private static final RequirementType[] VALUES = values();
        private final String translationKey =
            "betterquesting.btn.prereq_visbility." + name().toLowerCase(Locale.ROOT);

        public byte id() {
            return (byte) ordinal();
        }

        public String getTranslationKey() {
            return translationKey;
        }

        public RequirementType next() {
            return VALUES[(ordinal() + 1) % VALUES.length];
        }

        public static RequirementType from(byte id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : NORMAL;
        }
    }
}
