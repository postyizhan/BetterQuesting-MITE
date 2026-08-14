package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class NameCacheTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000602");

    @Test
    void exactUpstreamNbtRoundTripPreservesUuidNameAndOperatorFlag() {
        NameCache names = new NameCache();
        NBTTagList source = new NBTTagList();
        source.appendTag(entry(ALICE, "Alice", true));
        source.appendTag(entry(BOB, "Bob", false));

        names.readFromNBT(source, false);

        assertEquals("Alice", names.getName(ALICE));
        assertEquals(ALICE, names.getUUID("aLiCe"));
        assertTrue(names.isOP(ALICE));
        assertFalse(names.isOP(BOB));
        assertEquals(List.of("Alice", "Bob"), names.getAllNames());
        NBTTagList restored = names.writeToNBT(new NBTTagList(), null);
        assertEquals(2, restored.tagCount());
        assertEquals(List.of("isOP", "name", "uuid"),
            NbtCompat.sortedKeys(NbtCompat.getCompoundAt(restored, 0)));
        NameCache roundTripped = new NameCache();
        roundTripped.readFromNBT(restored, false);
        assertEquals("Alice", roundTripped.getName(ALICE));
        assertEquals("Bob", roundTripped.getName(BOB));
        assertTrue(roundTripped.isOP(ALICE));
        assertFalse(roundTripped.isOP(BOB));
    }

    @Test
    void subsetMergeResetAndFallbackMatchTheUpstreamDomainContract() {
        NameCache names = new NameCache();
        assertTrue(names.updateName(ALICE, "Alice", true));
        assertFalse(names.updateName(ALICE, "Alice", true));
        names.updateName(BOB, "Bob", false);

        NBTTagList subset = names.writeToNBT(new NBTTagList(), List.of(BOB));
        assertEquals(1, subset.tagCount());
        assertEquals(BOB.toString(), NbtCompat.getCompoundAt(subset, 0).getString("uuid"));
        assertEquals("00000000-0000-0000-0000-000000000699", names.getName(UUID.fromString(
            "00000000-0000-0000-0000-000000000699")));

        NBTTagList merge = new NBTTagList();
        merge.appendTag(entry(ALICE, "Renamed", false));
        names.readFromNBT(merge, true);
        assertEquals("Renamed", names.getName(ALICE));
        assertEquals("Bob", names.getName(BOB));
        names.reset();
        assertEquals(0, names.size());
    }

    @Test
    void ambiguousNamesNeverResolveToAnInventedIdentity() {
        NameCache names = new NameCache();
        names.updateName(ALICE, "SameName", false);
        names.updateName(BOB, "samename", true);

        assertNull(names.getUUID("SAMENAME"));
        assertEquals(2, names.size());
    }

    @Test
    void invalidRecordsAreIgnoredAndDuplicateUuidUsesLastRecordLikeUpstream() {
        NameCache names = new NameCache();
        NBTTagList source = new NBTTagList();
        NBTTagCompound invalid = entry(ALICE, "Invalid", false);
        invalid.setString("uuid", "not-a-uuid");
        source.appendTag(invalid);
        source.appendTag(entry(ALICE, "First", false));
        source.appendTag(entry(ALICE, "Last", true));

        names.readFromNBT(source, false);

        assertEquals(1, names.size());
        assertEquals("Last", names.getName(ALICE));
        assertTrue(names.isOP(ALICE));
    }

    @Test
    void equalEntriesSerializeIdenticallyRegardlessOfInsertionOrder() {
        NameCache first = new NameCache();
        first.updateName(BOB, "Bob", false);
        first.updateName(ALICE, "Alice", true);
        NameCache second = new NameCache();
        second.updateName(ALICE, "Alice", true);
        second.updateName(BOB, "Bob", false);

        assertEquals(serialized(first), serialized(second));
    }

    private static String serialized(NameCache names) {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("nameCache", names.writeToNBT(new NBTTagList(), null));
        return new NbtJsonCodec().toJson(root, true).toString();
    }

    private static NBTTagCompound entry(UUID uuid, String name, boolean operator) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("uuid", uuid.toString());
        entry.setString("name", name);
        entry.setBoolean("isOP", operator);
        return entry;
    }
}
