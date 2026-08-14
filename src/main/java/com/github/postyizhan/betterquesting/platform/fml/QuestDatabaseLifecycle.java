package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.storage.QuestDatabasePersistence;
import java.io.IOException;
import java.util.Objects;

/** Server lifecycle for the shared quest and quest-line document. */
public final class QuestDatabaseLifecycle {
    private final WorldStorage storage;
    private final QuestDatabasePersistence persistence;
    private final String build;
    private boolean retryOnWorldSave;

    public QuestDatabaseLifecycle(
        WorldStorage storage,
        QuestDatabase quests,
        QuestLineDatabase questLines,
        String build
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.persistence = new QuestDatabasePersistence(
            quests,
            questLines,
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
            persistence.clearDatabases();
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
            persistence.clearDatabases();
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    public void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted || persistence.isWritesDisabled()) {
            retryOnWorldSave = false;
            persistence.clearDatabases();
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
            persistence.clearDatabases();
        }
    }

    public boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }
}
