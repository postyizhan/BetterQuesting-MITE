package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.BoundedNbtWireCodec;
import com.github.postyizhan.betterquesting.network.NbtLimits;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class LoginNameSnapshotCodecTest {
    private static final UUID PLAYER_ID =
        UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final NbtLimits WIDE = new NbtLimits(
        16, 64, 16, 16, 128, 128, 128, 4_096L);

    @Test
    void roundTripsOnlyTheCanonicalUuidAndCachedDisplayNameAtExactBounds() {
        LoginNameSnapshot snapshot = new LoginNameSnapshot(PLAYER_ID, "Player_Name_1234");

        byte[] encoded = LoginNameSnapshotCodec.encode(snapshot);

        assertEquals(LoginNameSnapshotCodec.MAX_ENCODED_BYTES, encoded.length);
        assertEquals(snapshot, LoginNameSnapshotCodec.decode(encoded).orElseThrow());
        assertEquals("betterquesting:login_name", snapshot.formatId());
        assertEquals(1, snapshot.formatVersion());
    }

    @Test
    void rejectsInvalidDisplayNamesBeforeEncoding() {
        assertThrows(NullPointerException.class,
            () -> new LoginNameSnapshot(null, "Alice"));
        assertThrows(NullPointerException.class,
            () -> new LoginNameSnapshot(PLAYER_ID, null));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginNameSnapshot(PLAYER_ID, ""));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginNameSnapshot(PLAYER_ID, "Player_Name_12345"));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginNameSnapshot(PLAYER_ID, "Alice-1"));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginNameSnapshot(PLAYER_ID, "\ud800"));
    }

    @Test
    void rejectsWrongFieldTypesMissingFieldsAndEveryExtraField() {
        NBTTagCompound namedRoot = new NBTTagCompound("login");
        namedRoot.setString("uuid", PLAYER_ID.toString());
        namedRoot.setString("name", "Alice");
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(namedRoot, WIDE)).isEmpty());

        NBTTagCompound wrongUuid = root(PLAYER_ID.toString(), "Alice");
        wrongUuid.setLong("uuid", PLAYER_ID.getMostSignificantBits());
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(wrongUuid, WIDE)).isEmpty());

        NBTTagCompound wrongName = root(PLAYER_ID.toString(), "Alice");
        wrongName.setInteger("name", 7);
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(wrongName, WIDE)).isEmpty());

        NBTTagCompound nestedName = new NBTTagCompound();
        nestedName.setString("value", "Alice");
        NBTTagCompound overDepthAndNodes = new NBTTagCompound();
        overDepthAndNodes.setString("uuid", PLAYER_ID.toString());
        overDepthAndNodes.setTag("name", nestedName);
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(overDepthAndNodes, WIDE)).isEmpty());

        NBTTagCompound missingName = new NBTTagCompound();
        missingName.setString("uuid", PLAYER_ID.toString());
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(missingName, WIDE)).isEmpty());

        NBTTagCompound extra = root(PLAYER_ID.toString(), "Alice");
        extra.setBoolean("isOP", true);
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(extra, WIDE)).isEmpty());
    }

    @Test
    void rejectsNonCanonicalUuidAndOverBoundedStrings() {
        NBTTagCompound uppercaseUuid = root(PLAYER_ID.toString().toUpperCase(), "Alice");
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(uppercaseUuid, WIDE)).isEmpty());

        NBTTagCompound malformedUuid = root("not-a-uuid", "Alice");
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(malformedUuid, WIDE)).isEmpty());

        NBTTagCompound oversizedName = root(PLAYER_ID.toString(), "Player_Name_12345");
        assertTrue(LoginNameSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(oversizedName, WIDE)).isEmpty());
    }

    @Test
    void rejectsMalformedTruncatedTrailingAndOversizedNbtBodies() {
        byte[] encoded = LoginNameSnapshotCodec.encode(
            new LoginNameSnapshot(PLAYER_ID, "Alice"));

        assertTrue(LoginNameSnapshotCodec.decode(null).isEmpty());
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginNameSnapshotCodec.decode(Arrays.copyOf(encoded, length)).isEmpty(),
                "accepted truncation at byte " + length);
        }
        assertTrue(LoginNameSnapshotCodec.decode(
            Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());
        assertTrue(LoginNameSnapshotCodec.decode(
            new byte[LoginNameSnapshotCodec.MAX_ENCODED_BYTES + 1]).isEmpty());

        byte[] hostileArrayLength = new byte[] {
            118, 0, 0,
            121, 0, 4, 'u', 'u', 'i', 'd',
            127, 0, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            0
        };
        assertTrue(LoginNameSnapshotCodec.decode(hostileArrayLength).isEmpty());
        assertThrows(NullPointerException.class, () -> LoginNameSnapshotCodec.encode(null));
    }

    private static NBTTagCompound root(String uuid, String name) {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("uuid", uuid);
        root.setString("name", name);
        return root;
    }
}
