package com.github.postyizhan.betterquesting.questing.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.party.IParty;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PartyManagerTest {
    private static final UUID USER = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID OTHER = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @AfterEach
    void restoreSettings() {
        QuestSettings.INSTANCE.setProperty(NativeProps.PARTY_ENABLE, true);
    }

    @Test
    void createsOnlyNonNegativeIds() {
        PartyManager manager = new PartyManager();

        IParty detached = manager.createNew(-1);
        IParty stored = manager.createNew(3);

        assertEquals(1, manager.size());
        assertEquals(-1, manager.getID(detached));
        assertSame(stored, manager.getValue(3));
    }

    @Test
    void cacheHitReturnsSameParty() {
        PartyManager manager = managerWithUser(1, USER);
        DBEntry<IParty> first = manager.getParty(USER);

        DBEntry<IParty> second = manager.getParty(UUID.fromString(USER.toString()));

        assertEquals(1, first.getID());
        assertEquals(1, second.getID());
        assertSame(first.getValue(), second.getValue());
    }

    @Test
    void disbandedCachedPartyIsClearedAndAnotherPartyCanBeFound() {
        PartyManager manager = managerWithUser(1, USER);
        assertEquals(1, manager.getParty(USER).getID());
        manager.removeID(1);
        IParty replacement = manager.createNew(2);
        replacement.setStatus(USER, EnumPartyStatus.MEMBER);

        assertEquals(2, manager.getParty(USER).getID());
    }

    @Test
    void cachedPartyWithoutMembershipIsClearedAndAnotherPartyCanBeFound() {
        PartyManager manager = managerWithUser(1, USER);
        IParty oldParty = manager.getValue(1);
        assertEquals(1, manager.getParty(USER).getID());
        oldParty.kickUser(USER);
        IParty replacement = manager.createNew(2);
        replacement.setStatus(USER, EnumPartyStatus.MEMBER);

        assertEquals(2, manager.getParty(USER).getID());
    }

    @Test
    void disabledPartiesReturnNullWithoutDeletingData() {
        PartyManager manager = managerWithUser(1, USER);
        QuestSettings.INSTANCE.setProperty(NativeProps.PARTY_ENABLE, false);

        assertNull(manager.getParty(USER));
        assertEquals(1, manager.size());
    }

    @Test
    void writesOnlyRequestedSubsetWithPartyIdLiteral() {
        PartyManager manager = managerWithUser(1, USER);
        IParty otherParty = manager.createNew(2);
        otherParty.setStatus(OTHER, EnumPartyStatus.OWNER);

        NBTTagList serialized = manager.writeToNBT(new NBTTagList(), List.of(2));

        assertEquals(1, serialized.tagCount());
        NBTTagCompound partyTag = (NBTTagCompound) serialized.tagAt(0);
        assertEquals(2, partyTag.getInteger("partyID"));
        assertTrue(partyTag.hasKey("members"));
        assertTrue(partyTag.hasKey("properties"));
    }

    @Test
    void readSkipsMissingNegativeAndEmptyParties() {
        NBTTagList serialized = new NBTTagList();
        serialized.appendTag(partyTag(4, USER));
        serialized.appendTag(partyTag(-1, OTHER));
        NBTTagCompound missingId = partyTag(5, OTHER);
        missingId.removeTag("partyID");
        serialized.appendTag(missingId);
        NBTTagCompound empty = new PartyInstance().writeToNBT(new NBTTagCompound());
        empty.setInteger("partyID", 6);
        serialized.appendTag(empty);

        PartyManager manager = new PartyManager();
        manager.readFromNBT(serialized, false);

        assertEquals(1, manager.size());
        assertEquals(4, manager.getParty(USER).getID());
        assertNull(manager.getValue(6));
    }

    @Test
    void mergeKeepsExistingEntriesAndNonMergeResetsThem() {
        PartyManager manager = managerWithUser(1, USER);
        NBTTagList serialized = new NBTTagList();
        serialized.appendTag(partyTag(2, OTHER));

        manager.readFromNBT(serialized, true);
        assertEquals(2, manager.size());
        manager.readFromNBT(serialized, false);

        assertEquals(1, manager.size());
        assertNull(manager.getValue(1));
        assertEquals(2, manager.getParty(OTHER).getID());
    }

    @Test
    void resetClearsEntriesAndCachedMembership() {
        PartyManager manager = managerWithUser(1, USER);
        assertEquals(1, manager.getParty(USER).getID());

        manager.reset();

        assertEquals(0, manager.size());
        assertNull(manager.getParty(USER));
        assertFalse(manager.getEntries().iterator().hasNext());
    }

    private static PartyManager managerWithUser(int id, UUID user) {
        PartyManager manager = new PartyManager();
        IParty party = manager.createNew(id);
        party.setStatus(user, EnumPartyStatus.OWNER);
        return manager;
    }

    private static NBTTagCompound partyTag(int id, UUID user) {
        PartyInstance party = new PartyInstance();
        party.setStatus(user, EnumPartyStatus.OWNER);
        NBTTagCompound tag = party.writeToNBT(new NBTTagCompound());
        tag.setInteger("partyID", id);
        return tag;
    }
}
