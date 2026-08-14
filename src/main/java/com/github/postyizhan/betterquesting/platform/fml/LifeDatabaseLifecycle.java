package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.LifeDatabase;
import com.github.postyizhan.betterquesting.storage.LifeDatabasePersistence;
import java.io.IOException;
import java.util.Objects;

/** Server lifecycle for the independently stored world life database. */
public final class LifeDatabaseLifecycle {
    private final WorldStorage storage;
    private final LifeDatabase lives;
    private final LifeDatabasePersistence persistence;
    private final String build;
    private boolean retryOnWorldSave;

    public LifeDatabaseLifecycle(WorldStorage storage, LifeDatabase lives, String build) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.lives = Objects.requireNonNull(lives, "lives");
        this.persistence = new LifeDatabasePersistence(lives, new JsonDocumentStore(storage));
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
            lives.reset();
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
            lives.reset();
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    public void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted || persistence.isWritesDisabled()) {
            retryOnWorldSave = false;
            lives.reset();
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
            lives.reset();
        }
    }

    public boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }
}
