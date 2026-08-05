package com.github.postyizhan.betterquesting.questing.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class PartyInstanceTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void kickingOwnerMigratesToAdminBeforeMember() {
        PartyInstance party = partyWithRoles();

        party.kickUser(OWNER);

        assertEquals(EnumPartyStatus.OWNER, party.getStatus(ADMIN));
        assertEquals(EnumPartyStatus.MEMBER, party.getStatus(MEMBER));
        assertFalse(party.getMembers().contains(OWNER));
    }

    @Test
    void kickingLastMemberLeavesEmptyParty() {
        PartyInstance party = new PartyInstance();
        party.setStatus(OWNER, EnumPartyStatus.OWNER);

        party.kickUser(OWNER);

        assertTrue(party.getMembers().isEmpty());
    }

    @Test
    void promotingOwnerDemotesPreviousOwner() {
        PartyInstance party = partyWithRoles();

        party.setStatus(MEMBER, EnumPartyStatus.OWNER);

        assertEquals(EnumPartyStatus.ADMIN, party.getStatus(OWNER));
        assertEquals(EnumPartyStatus.OWNER, party.getStatus(MEMBER));
    }

    @Test
    void equalDistinctUuidPromotionUsesValueEquality() {
        PartyInstance party = partyWithRoles();
        UUID decodedMember = new UUID(MEMBER.getMostSignificantBits(), MEMBER.getLeastSignificantBits());

        party.setStatus(decodedMember, EnumPartyStatus.OWNER);

        // Upstream's `member != uuid` treats the retained HashMap key as a different user and demotes it again.
        assertEquals(EnumPartyStatus.OWNER, party.getStatus(MEMBER));
        assertEquals(EnumPartyStatus.ADMIN, party.getStatus(OWNER));
    }

    @Test
    void ownerPromotionUsesStableSnapshotWhileUpdatingRoles() {
        PartyInstance party = new PartyInstance();
        NBTTagCompound serialized = new NBTTagCompound();
        serialized.setTag("properties", new NBTTagCompound());
        NBTTagList members = new NBTTagList();
        members.appendTag(memberTag(OWNER.toString(), "OWNER"));
        members.appendTag(memberTag(ADMIN.toString(), "OWNER"));
        members.appendTag(memberTag(MEMBER.toString(), "MEMBER"));
        serialized.setTag("members", members);
        party.readFromNBT(serialized);

        party.setStatus(MEMBER, EnumPartyStatus.OWNER);

        // Upstream happens to be safe only because getMembers() currently returns an independent cached copy.
        assertEquals(EnumPartyStatus.ADMIN, party.getStatus(OWNER));
        assertEquals(EnumPartyStatus.ADMIN, party.getStatus(ADMIN));
        assertEquals(EnumPartyStatus.OWNER, party.getStatus(MEMBER));
    }

    @Test
    void ownerResignationWithoutSuccessorRollsBack() {
        PartyInstance party = new PartyInstance();
        party.setStatus(OWNER, EnumPartyStatus.OWNER);

        party.setStatus(UUID.fromString(OWNER.toString()), EnumPartyStatus.MEMBER);

        assertEquals(EnumPartyStatus.OWNER, party.getStatus(OWNER));
    }

    @Test
    void ownerResignationPrefersAdmin() {
        PartyInstance party = partyWithRoles();

        party.setStatus(UUID.fromString(OWNER.toString()), EnumPartyStatus.MEMBER);

        assertEquals(EnumPartyStatus.MEMBER, party.getStatus(OWNER));
        assertEquals(EnumPartyStatus.OWNER, party.getStatus(ADMIN));
        assertEquals(EnumPartyStatus.MEMBER, party.getStatus(MEMBER));
    }

    @Test
    void memberViewIsImmutableAndRefreshesAfterChanges() {
        PartyInstance party = new PartyInstance();
        party.setStatus(OWNER, EnumPartyStatus.OWNER);
        List<UUID> before = party.getMembers();
        assertThrows(UnsupportedOperationException.class, () -> before.add(MEMBER));

        party.setStatus(MEMBER, EnumPartyStatus.MEMBER);

        assertEquals(List.of(OWNER), before);
        assertTrue(party.getMembers().containsAll(List.of(OWNER, MEMBER)));
    }

    @Test
    void newFormatRoundTripsMembersAndProperties() {
        PartyInstance original = partyWithRoles();
        original.getProperties().setProperty(NativeProps.NAME, "Round Trip Party");

        NBTTagCompound serialized = original.writeToNBT(new NBTTagCompound());
        PartyInstance loaded = new PartyInstance();
        loaded.readFromNBT(serialized);

        assertTrue(serialized.hasKey("members"));
        assertTrue(serialized.hasKey("properties"));
        assertEquals("Round Trip Party", loaded.getProperties().getProperty(NativeProps.NAME));
        assertEquals(EnumPartyStatus.OWNER, loaded.getStatus(OWNER));
        assertEquals(EnumPartyStatus.ADMIN, loaded.getStatus(ADMIN));
        assertEquals(EnumPartyStatus.MEMBER, loaded.getStatus(MEMBER));
    }

    @Test
    void legacyNameLoadsWithoutPropertiesCompound() {
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setString("name", "Legacy Party");

        PartyInstance party = new PartyInstance();
        party.readFromNBT(legacy);

        assertEquals("Legacy Party", party.getProperties().getProperty(NativeProps.NAME));
        assertTrue(party.getMembers().isEmpty());
    }

    @Test
    void malformedMembersAreSkippedWhileValidEntriesLoad() {
        NBTTagCompound serialized = new NBTTagCompound();
        serialized.setTag("properties", new NBTTagCompound());
        NBTTagList members = new NBTTagList();
        members.appendTag(memberTag("not-a-uuid", "OWNER"));
        members.appendTag(memberTag(ADMIN.toString(), "NOT_A_ROLE"));
        members.appendTag(new NBTTagCompound());
        members.appendTag(memberTag(MEMBER.toString(), "MEMBER"));
        serialized.setTag("members", members);

        PartyInstance party = new PartyInstance();
        party.readFromNBT(serialized);

        assertEquals(1, party.getMembers().size());
        assertEquals(EnumPartyStatus.MEMBER, party.getStatus(MEMBER));
    }

    @Test
    void missingMembersKeyLoadsAsEmpty() {
        NBTTagCompound serialized = new NBTTagCompound();
        serialized.setTag("properties", new NBTTagCompound());

        PartyInstance party = new PartyInstance();
        party.readFromNBT(serialized);

        assertTrue(party.getMembers().isEmpty());
        assertEquals("New Party", party.getProperties().getProperty(NativeProps.NAME));
    }

    private static PartyInstance partyWithRoles() {
        PartyInstance party = new PartyInstance();
        party.setStatus(OWNER, EnumPartyStatus.OWNER);
        party.setStatus(ADMIN, EnumPartyStatus.ADMIN);
        party.setStatus(MEMBER, EnumPartyStatus.MEMBER);
        return party;
    }

    private static NBTTagCompound memberTag(String uuid, String status) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("uuid", uuid);
        tag.setString("status", status);
        return tag;
    }
}
