package com.github.postyizhan.betterquesting.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.util.NbtUuid.UuidValueType;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class NbtUuidTest {
    private static final UUID ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void binaryIdRoundTripsAndRequiresBothNumericFields() {
        NBTTagCompound tag = UuidValueType.QUEST.writeId(ID);
        assertTrue(tag.hasKey("questIDHigh"));
        assertTrue(tag.hasKey("questIDLow"));
        assertEquals(ID, UuidValueType.QUEST.readId(tag));
        assertEquals(ID, UuidValueType.QUEST.tryReadId(tag).orElseThrow());

        tag.removeTag("questIDLow");
        assertTrue(UuidValueType.QUEST.tryReadId(tag).isEmpty());
        tag.setString("questIDLow", "wrong type");
        assertTrue(UuidValueType.QUEST.tryReadId(tag).isEmpty());
    }

    @Test
    void stringIdSupportsEncodedLegacyAndEmptyValues() {
        NBTTagCompound tag = new NBTTagCompound();
        UuidValueType.QUEST.writeIdString(ID, tag);
        assertEquals(ID, UuidValueType.QUEST.tryReadIdString(tag).orElseThrow());

        tag.setString("questID", ID.toString());
        assertEquals(ID, UuidValueType.QUEST.tryReadIdString(tag).orElseThrow());
        UuidValueType.QUEST.writeIdString(null, tag);
        assertTrue(UuidValueType.QUEST.tryReadIdString(tag).isEmpty());
        assertTrue(UuidValueType.QUEST_LINE.tryReadIdString(tag).isEmpty());
    }

    @Test
    void idListsRoundTripSkipNonCompoundsAndRejectWrongContainerType() {
        UUID second = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagList ids = UuidValueType.QUEST.writeIds(List.of(ID, second));
        assertEquals(List.of(ID, second), UuidValueType.QUEST.readIds(ids));

        NBTTagCompound parent = new NBTTagCompound();
        parent.setTag("ids", ids);
        assertEquals(List.of(ID, second), UuidValueType.QUEST.readIds(parent, "ids"));
        parent.setString("ids", "not a list");
        assertTrue(UuidValueType.QUEST.readIds(parent, "ids").isEmpty());
        parent.setString("questLines", "not a list");
        assertTrue(UuidValueType.QUEST_LINE.readIds(parent, "questLines").isEmpty());
        assertFalse(parent.getTag("ids").getId() == 9);
    }
}
