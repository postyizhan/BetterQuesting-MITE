package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.party.PartyManager;
import com.github.postyizhan.betterquesting.storage.PartyManagerPersistence;
import java.io.IOException;
import java.util.Objects;

/** Server lifecycle for the world party document. */
public final class PartyManagerLifecycle {
    private final WorldStorage storage;
    private final PartyManagerPersistence persistence;
    private final String build;
    private boolean retryOnWorldSave;

    public PartyManagerLifecycle(
        WorldStorage storage,
        PartyManager parties,
        String build
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.persistence = new PartyManagerPersistence(
            parties,
            new JsonDocumentStore(storage));
        this.build = build;
    }

    public JsonDocumentStore.Outcome onServerStarted() throws IOException {
        return persistence.load();
    }

    public void onWorldSave() throws IOException {
        onWorldSave(false);
    }

    public void onWorldSave(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted) {
            retryOnWorldSave = false;
            persistence.clearParties();
            return;
        }
        if (!retryOnWorldSave) {
            persistence.save(build);
            return;
        }

        try {
            persistence.save(build);
        } finally {
            retryOnWorldSave = false;
            persistence.clearParties();
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    public void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted || persistence.isWritesDisabled()) {
            retryOnWorldSave = false;
            persistence.clearParties();
            return;
        }

        try {
            persistence.save(build);
        } catch (IOException | RuntimeException failure) {
            retryOnWorldSave = true;
            throw failure;
        }

        try {
            storage.flush();
        } finally {
            retryOnWorldSave = false;
            persistence.clearParties();
        }
    }

    public boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }
}
