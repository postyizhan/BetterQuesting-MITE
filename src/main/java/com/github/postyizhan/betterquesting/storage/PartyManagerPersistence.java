package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.questing.party.IParty;
import com.github.postyizhan.betterquesting.api.storage.DBEntry;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/** Persistence boundary for the upstream-compatible world party document. */
public final class PartyManagerPersistence {
    public static final String PATH = "QuestingParties.json";

    private final PartyManager parties;
    private final JsonDocumentStore store;
    private boolean writesDisabled;

    public PartyManagerPersistence(PartyManager parties, JsonDocumentStore store) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.store = Objects.requireNonNull(store, "store");
    }

    public synchronized JsonDocumentStore.Outcome load() throws IOException {
        writesDisabled = true;
        clearParties();
        JsonDocumentStore.LoadResult result;
        try {
            result = store.load(PATH, true);
        } catch (IOException | RuntimeException failure) {
            clearParties();
            throw failure;
        }

        if (result.outcome() != JsonDocumentStore.Outcome.LOADED) {
            clearParties();
            writesDisabled = result.outcome() != JsonDocumentStore.Outcome.ABSENT;
            return result.outcome();
        }
        if (!JsonSchemaFields.isCompatibleMitePortFormat(result.root())
            || !validRoot(result.root())) {
            clearParties();
            store.quarantine(PATH);
            return JsonDocumentStore.Outcome.QUARANTINED;
        }

        PartyManager loaded = new PartyManager();
        try {
            loaded.readFromNBT(NbtCompat.getListOrEmpty(result.root(), "parties"), false);
            replaceParties(loaded);
        } catch (RuntimeException failure) {
            clearParties();
            throw failure;
        }
        writesDisabled = false;
        return result.outcome();
    }

    public synchronized void save(String build) throws IOException {
        if (writesDisabled) {
            return;
        }
        NBTTagCompound root = new NBTTagCompound();
        synchronized (parties) {
            root.setTag("parties", parties.writeToNBT(new NBTTagList(), null));
        }
        JsonSchemaFields.stamp(root, build);
        store.save(PATH, root, true);
    }

    public synchronized boolean isWritesDisabled() {
        return writesDisabled;
    }

    public void clearParties() {
        parties.reset();
    }

    private boolean validRoot(NBTTagCompound root) {
        return root.hasKey("parties") && NbtCompat.getTagId(root, "parties") == 9;
    }

    private void replaceParties(PartyManager loaded) {
        synchronized (parties) {
            parties.reset();
            for (DBEntry<IParty> entry : loaded.getEntries()) {
                parties.add(entry.getID(), entry.getValue());
            }
        }
    }
}
