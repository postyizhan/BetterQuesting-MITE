package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LifeDatabaseTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000602");

    @AfterEach
    void restoreSettings() {
        QuestSettings.INSTANCE.reset();
    }

    @Test
    void exactUpstreamNbtRoundTripPreservesStoredLivesWithoutLoadTimeClamp() {
        LifeDatabase lives = new LifeDatabase();
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList players = new NBTTagList();
        players.appendTag(entry(ALICE, 4));
        players.appendTag(entry(BOB, -2));
        source.setTag("playerLives", players);

        lives.readFromNBT(source, false);

        assertEquals(4, lives.getLives(ALICE));
        assertEquals(-2, lives.getLives(BOB));
        NBTTagCompound restored = lives.writeToNBT(new NBTTagCompound(), null);
        assertEquals(2, restored.getTagList("playerLives").tagCount());
        LifeDatabase roundTripped = new LifeDatabase();
        roundTripped.readFromNBT(restored, false);
        assertEquals(4, roundTripped.getLives(ALICE));
        assertEquals(-2, roundTripped.getLives(BOB));
    }

    @Test
    void defaultsSetClampSubsetMergeAndResetMatchUpstream() {
        QuestSettings.INSTANCE.setProperty(NativeProps.LIVES_DEF, 6);
        QuestSettings.INSTANCE.setProperty(NativeProps.LIVES_MAX, 8);
        LifeDatabase lives = new LifeDatabase();

        assertEquals(6, lives.getLives(ALICE));
        lives.setLives(ALICE, 99);
        lives.setLives(BOB, -1);
        assertEquals(8, lives.getLives(ALICE));
        assertEquals(0, lives.getLives(BOB));
        NBTTagList subset = lives.writeToNBT(new NBTTagCompound(), List.of(BOB))
            .getTagList("playerLives");
        assertEquals(1, subset.tagCount());
        assertEquals(BOB.toString(), NbtCompat.getCompoundAt(subset, 0).getString("uuid"));

        NBTTagCompound merge = new NBTTagCompound();
        NBTTagList players = new NBTTagList();
        players.appendTag(entry(ALICE, 3));
        merge.setTag("playerLives", players);
        lives.readFromNBT(merge, true);
        assertEquals(3, lives.getLives(ALICE));
        assertEquals(0, lives.getLives(BOB));
        lives.reset();
        assertEquals(6, lives.getLives(ALICE));
    }

    @Test
    void invalidRecordsAreIgnoredAndDuplicateUuidUsesLastRecord() {
        LifeDatabase lives = new LifeDatabase();
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList players = new NBTTagList();
        NBTTagCompound invalid = entry(ALICE, 1);
        invalid.setString("uuid", "bad");
        players.appendTag(invalid);
        players.appendTag(entry(ALICE, 2));
        players.appendTag(entry(ALICE, 5));
        source.setTag("playerLives", players);

        lives.readFromNBT(source, false);

        assertEquals(5, lives.getLives(ALICE));
        assertEquals(1, lives.writeToNBT(new NBTTagCompound(), null)
            .getTagList("playerLives").tagCount());
    }

    @Test
    void equalEntriesSerializeIdenticallyRegardlessOfInsertionOrder() {
        LifeDatabase first = new LifeDatabase();
        first.setLives(BOB, 3);
        first.setLives(ALICE, 4);
        LifeDatabase second = new LifeDatabase();
        second.setLives(ALICE, 4);
        second.setLives(BOB, 3);

        assertEquals(serialized(first), serialized(second));
    }

    private static String serialized(LifeDatabase lives) {
        return new NbtJsonCodec().toJson(lives.writeToNBT(new NBTTagCompound(), null), true).toString();
    }

    private static NBTTagCompound entry(UUID uuid, int count) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("uuid", uuid.toString());
        entry.setInteger("lives", count);
        return entry;
    }
}
