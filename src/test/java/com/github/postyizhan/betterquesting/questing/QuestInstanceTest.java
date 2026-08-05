package com.github.postyizhan.betterquesting.questing;

import static org.junit.jupiter.api.Assertions.*;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.IQuest.RequirementType;
import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import com.github.postyizhan.betterquesting.api.util.UuidConverter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class QuestInstanceTest {
    @Test
    void requirementsAndTypesFollowUpstreamRules() {
        QuestInstance quest = new QuestInstance();
        UUID first = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        quest.getRequirements().add(first);
        quest.setRequirementType(first, RequirementType.IMPLICIT);
        quest.setRequirementType(orphan, RequirementType.HIDDEN);
        quest.setRequirements(List.of(first));
        assertEquals(RequirementType.IMPLICIT, quest.getRequirementType(first));
        assertEquals(RequirementType.NORMAL, quest.getRequirementType(orphan));
        quest.setRequirementType(first, RequirementType.NORMAL);
        assertEquals(RequirementType.NORMAL, quest.getRequirementType(first));
    }

    @Test
    void requirementTypeIdsAndTranslationKeysAreStable() {
        assertEquals(0, RequirementType.NORMAL.ordinal());
        assertEquals(1, RequirementType.IMPLICIT.ordinal());
        assertEquals(2, RequirementType.HIDDEN.ordinal());
        for (RequirementType type : RequirementType.values()) assertSame(type, RequirementType.from(type.id()));
        assertEquals("betterquesting.btn.prereq_visbility.normal", RequirementType.NORMAL.getTranslationKey());
        assertEquals("betterquesting.btn.prereq_visbility.implicit", RequirementType.IMPLICIT.getTranslationKey());
        assertEquals("betterquesting.btn.prereq_visbility.hidden", RequirementType.HIDDEN.getTranslationKey());
    }

    @Test
    void readsModernPrerequisiteList() {
        UUID id = UUID.randomUUID();
        NBTTagCompound requirement = UuidValueType.QUEST.writeId(id);
        requirement.setByte("type", RequirementType.HIDDEN.id());
        NBTTagList list = new NBTTagList();
        list.appendTag(requirement);
        NBTTagCompound saved = emptyConfig();
        saved.setTag("preRequisites", list);
        QuestInstance quest = new QuestInstance();
        quest.readFromNBT(saved);
        assertEquals(Set.of(id), quest.getRequirements());
        assertEquals(RequirementType.HIDDEN, quest.getRequirementType(id));
    }

    @Test
    void readsLegacyPrerequisitesAndTypesByIndex() {
        NBTTagCompound saved = emptyConfig();
        saved.setIntArray("preRequisites", new int[] {12, 35});
        saved.setByteArray("preRequisiteTypes", new byte[] {1, 2});
        QuestInstance quest = new QuestInstance();
        quest.readFromNBT(saved);
        UUID first = UuidConverter.convertLegacyId(12);
        UUID second = UuidConverter.convertLegacyId(35);
        assertEquals(RequirementType.IMPLICIT, quest.getRequirementType(first));
        assertEquals(RequirementType.HIDDEN, quest.getRequirementType(second));
    }

    @Test
    void completionProgressRoundTripsAndClaimBranchesWork() {
        UUID existing = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        QuestInstance quest = new QuestInstance();
        quest.setComplete(existing, 10L);
        quest.setClaimed(existing, 20L);
        quest.setClaimed(absent, 30L);
        assertTrue(quest.getCompletionInfo(existing).getBoolean("claimed"));
        assertEquals(20L, quest.getCompletionInfo(existing).getLong("timestamp"));
        assertEquals(30L, quest.getCompletionInfo(absent).getLong("timestamp"));
        NBTTagCompound progress = quest.writeProgressToNBT(new NBTTagCompound(), null);
        QuestInstance loaded = new QuestInstance();
        loaded.readProgressFromNBT(progress, false);
        assertEquals(20L, loaded.getCompletionInfo(existing).getLong("timestamp"));
        assertEquals(30L, loaded.getCompletionInfo(absent).getLong("timestamp"));
    }

    @Test
    void countAsQuestIsPrepopulatedWhenMissing() {
        QuestInstance quest = new QuestInstance();
        quest.readFromNBT(emptyConfig());
        NBTTagCompound written = quest.writeToNBT(new NBTTagCompound());
        assertTrue(written.getCompoundTag("properties").getCompoundTag("betterquesting").hasKey("countAsQuest"));
        assertTrue(quest.getProperty(NativeProps.COUNT_AS_QUEST));
    }

    private static NBTTagCompound emptyConfig() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("properties", new NBTTagCompound());
        tag.setTag("tasks", new NBTTagList());
        tag.setTag("rewards", new NBTTagList());
        return tag;
    }

    @Test
    void rereadDropsRequirementTypesForRequirementsNoLongerPresent() {
        // Deliberate deviation: upstream readFromNBT never clears prereqTypes, so re-reading the same
        // QuestInstance leaves stale entries keyed by requirements the new NBT no longer declares. Those
        // entries are then written back out by writeToNBT, growing the save file with unreachable data.
        QuestInstance quest = new QuestInstance();
        UUID stale = UUID.randomUUID();
        UUID kept = UUID.randomUUID();

        NBTTagCompound first = emptyConfig();
        NBTTagList firstReqs = new NBTTagList();
        firstReqs.appendTag(requirementTag(stale, RequirementType.HIDDEN));
        firstReqs.appendTag(requirementTag(kept, RequirementType.IMPLICIT));
        first.setTag("preRequisites", firstReqs);
        quest.readFromNBT(first);

        assertEquals(RequirementType.HIDDEN, quest.getRequirementType(stale));
        assertEquals(RequirementType.IMPLICIT, quest.getRequirementType(kept));

        NBTTagCompound second = emptyConfig();
        NBTTagList secondReqs = new NBTTagList();
        secondReqs.appendTag(requirementTag(kept, RequirementType.IMPLICIT));
        second.setTag("preRequisites", secondReqs);
        quest.readFromNBT(second);

        assertEquals(Set.of(kept), quest.getRequirements());
        assertEquals(RequirementType.IMPLICIT, quest.getRequirementType(kept));
        assertEquals(RequirementType.NORMAL, quest.getRequirementType(stale));

        NBTTagList written = (NBTTagList) quest.writeToNBT(new NBTTagCompound()).getTag("preRequisites");
        assertEquals(1, written.tagCount());
    }

    @Test
    void corruptProgressRecordIsSkippedWithoutAbortingLaterRecords() {
        UUID valid = UUID.randomUUID();

        NBTTagList completed = new NBTTagList();
        NBTTagCompound corrupt = new NBTTagCompound();
        corrupt.setString("uuid", "not-a-uuid");
        corrupt.setBoolean("claimed", true);
        completed.appendTag(corrupt);
        NBTTagCompound good = new NBTTagCompound();
        good.setString("uuid", valid.toString());
        good.setBoolean("claimed", true);
        good.setLong("timestamp", 42L);
        completed.appendTag(good);

        NBTTagCompound progress = new NBTTagCompound();
        progress.setTag("completed", completed);
        progress.setTag("tasks", new NBTTagList());

        QuestInstance quest = new QuestInstance();
        quest.readProgressFromNBT(progress, false);

        // Locks the recovery contract: a corrupt record is dropped and the record after it still loads.
        // This input throws IllegalArgumentException, so it does not by itself prove the catch must be broad;
        // that width matches upstream, which catches Exception so untrusted save data can never abort the read.
        assertTrue(quest.isComplete(valid));
        assertTrue(quest.hasClaimed(valid));
        Set<UUID> loaded = new HashSet<>();
        quest.getUsersWithCompletionData(loaded);
        assertEquals(Set.of(valid), loaded);
    }

    private static NBTTagCompound requirementTag(UUID id, RequirementType type) {
        NBTTagCompound tag = new NBTTagCompound();
        UuidValueType.QUEST.writeId(id, tag);
        tag.setByte("type", type.id());
        return tag;
    }
}
