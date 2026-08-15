package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.NameCache;
import com.github.postyizhan.betterquesting.storage.NameCachePersistence;
import java.io.IOException;
import java.util.Objects;

/** Server lifecycle for the independently stored world name cache. */
public final class NameCacheLifecycle {
    private final WorldStorage storage;
    private final NameCache names;
    private final NameCachePersistence persistence;
    private final String build;
    private boolean retryOnWorldSave;

    public NameCacheLifecycle(WorldStorage storage, NameCache names, String build) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.names = Objects.requireNonNull(names, "names");
        this.persistence = new NameCachePersistence(names, new JsonDocumentStore(storage));
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
            names.reset();
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
            names.reset();
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    public void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted || persistence.isWritesDisabled()) {
            retryOnWorldSave = false;
            names.reset();
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
            names.reset();
        }
    }

    public boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }

    boolean isWritable() {
        return !retryOnWorldSave && !persistence.isWritesDisabled();
    }
}
