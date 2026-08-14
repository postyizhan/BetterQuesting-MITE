package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumPartyStatus;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.party.IParty;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocuments;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.questing.party.PartyInstance;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartyManagerPersistenceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000404");

    @TempDir
    Path dataDirectory;

    @BeforeEach
    void clearSingleton() {
        PartyManager.INSTANCE.reset();
        QuestSettings.INSTANCE.setProperty(NativeProps.PARTY_ENABLE, true);
    }

    @Test
    void realisticPartiesRoundTripWithStableIdsRolesAndOrdering() throws IOException {
        IParty later = PartyManager.INSTANCE.createNew(7);
        later.getProperties().setProperty(NativeProps.NAME, "Later Party");
        later.setStatus(OTHER_OWNER, EnumPartyStatus.OWNER);
        IParty earlier = PartyManager.INSTANCE.createNew(2);
        earlier.getProperties().setProperty(NativeProps.NAME, "The Explorers");
        earlier.setStatus(OWNER, EnumPartyStatus.OWNER);
        earlier.setStatus(ADMIN, EnumPartyStatus.ADMIN);
        earlier.setStatus(MEMBER, EnumPartyStatus.MEMBER);

        PartyManagerPersistence persistence = persistence(dataDirectory);
        persistence.save("build-43");
        PartyManager.INSTANCE.reset();

        assertEquals(JsonDocumentStore.Outcome.LOADED, persistence.load());
        assertEquals(List.of(2, 7), PartyManager.INSTANCE.getEntries().stream().map(DBEntry::getID).toList());
        IParty loaded = PartyManager.INSTANCE.getValue(2);
        assertEquals("The Explorers", loaded.getProperties().getProperty(NativeProps.NAME));
        assertEquals(EnumPartyStatus.OWNER, loaded.getStatus(OWNER));
        assertEquals(EnumPartyStatus.ADMIN, loaded.getStatus(ADMIN));
        assertEquals(EnumPartyStatus.MEMBER, loaded.getStatus(MEMBER));
        assertEquals(7, PartyManager.INSTANCE.getParty(OTHER_OWNER).getID());

        NBTTagCompound root = savedRoot(dataDirectory);
        NBTTagList parties = NbtCompat.getListOrEmpty(root, "parties");
        assertEquals(2, NbtCompat.getCompoundAt(parties, 0).getInteger("partyID"));
        assertEquals(7, NbtCompat.getCompoundAt(parties, 1).getInteger("partyID"));
        assertEquals("3.1.0", root.getString("format"));
        assertEquals("build-43", root.getString("build"));
        assertEquals("1", root.getString("mitePortFormat"));
        assertFalse(Files.exists(dataDirectory.resolve("QuestingParties.json.tmp")));
    }

    @Test
    void absentDocumentClearsStaleSingletonAndPermitsFirstSave() throws IOException {
        party(3, OWNER, "Stale");
        PartyManagerPersistence persistence = persistence(dataDirectory);

        assertEquals(JsonDocumentStore.Outcome.ABSENT, persistence.load());
        assertEquals(0, PartyManager.INSTANCE.size());
        party(5, MEMBER, "First Save");
        persistence.save("build-43");

        assertTrue(Files.exists(dataDirectory.resolve("QuestingParties.json")));
        assertFalse(persistence.isWritesDisabled());
    }

    @Test
    void validUpstreamDocumentWithoutPortMarkerLoads() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestingParties.json"),
            "{\"parties:9\":{\"0:10\":{\"partyID:3\":11,\"members:9\":{\"0:10\":{"
                + "\"uuid:8\":\"" + OWNER + "\",\"status:8\":\"OWNER\"}},"
                + "\"properties:10\":{\"betterquesting:10\":{\"name:8\":\"Upstream\"}}}}}",
            StandardCharsets.UTF_8);

        assertEquals(JsonDocumentStore.Outcome.LOADED, persistence(dataDirectory).load());
        assertEquals("Upstream", PartyManager.INSTANCE.getValue(11)
            .getProperties().getProperty(NativeProps.NAME));
        assertEquals(EnumPartyStatus.OWNER, PartyManager.INSTANCE.getValue(11).getStatus(OWNER));
    }

    @Test
    void duplicateIdsFailStagedLoadWithoutLeakingPartialPartiesOrQuarantine() throws IOException {
        NBTTagList parties = new NBTTagList();
        parties.appendTag(serializedParty(4, OWNER, "First"));
        parties.appendTag(serializedParty(4, OTHER_OWNER, "Duplicate"));
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("parties", parties);
        String document = new NbtJsonCodec().toJson(root, true).toString();
        Files.writeString(dataDirectory.resolve("QuestingParties.json"), document, StandardCharsets.UTF_8);
        party(9, MEMBER, "Stale");
        PartyManagerPersistence persistence = persistence(dataDirectory);

        assertThrows(IllegalArgumentException.class, persistence::load);

        assertEquals(0, PartyManager.INSTANCE.size());
        assertTrue(persistence.isWritesDisabled());
        assertFalse(Files.exists(dataDirectory.resolve("malformed_QuestingParties.json.json")));
        assertEquals(document, Files.readString(dataDirectory.resolve("QuestingParties.json")));
    }

    @Test
    void saveWritesOnlyTheUpstreamPartiesRootPlusSchemaStamps() throws IOException {
        party(13, OWNER, "Root Contract");

        persistence(dataDirectory).save("build-root");

        NBTTagCompound root = savedRoot(dataDirectory);
        assertEquals(List.of("build", "format", "mitePortFormat", "parties"), NbtCompat.sortedKeys(root));
        assertEquals(9, NbtCompat.getTagId(root, "parties"));
        assertNull(root.hasKey("questDatabase") ? root.getTag("questDatabase") : null);
    }

    private PartyManagerPersistence persistence(Path directory) {
        return new PartyManagerPersistence(
            PartyManager.INSTANCE,
            new JsonDocumentStore(new DirectoryWorldStorage(directory)));
    }

    private static IParty party(int id, UUID member, String name) {
        IParty party = PartyManager.INSTANCE.createNew(id);
        party.getProperties().setProperty(NativeProps.NAME, name);
        party.setStatus(member, EnumPartyStatus.OWNER);
        return party;
    }

    private static NBTTagCompound serializedParty(int id, UUID member, String name) {
        PartyInstance party = new PartyInstance();
        party.getProperties().setProperty(NativeProps.NAME, name);
        party.setStatus(member, EnumPartyStatus.OWNER);
        NBTTagCompound tag = party.writeToNBT(new NBTTagCompound());
        tag.setInteger("partyID", id);
        return tag;
    }

    private static NBTTagCompound savedRoot(Path directory) throws IOException {
        String document = Files.readString(directory.resolve("QuestingParties.json"), StandardCharsets.UTF_8);
        return new NbtJsonCodec().toNbt(JsonDocuments.parseObject(document), new NBTTagCompound(), true);
    }
}
