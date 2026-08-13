package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import com.github.postyizhan.betterquesting.storage.QuestSettings;
import com.github.postyizhan.betterquesting.storage.QuestSettingsPersistence;
import java.io.IOException;
import java.util.Objects;

/** Pure lifecycle seam used by the Minecraft event bridge and JVM tests. */
public final class QuestSettingsLifecycle {
    private final WorldStorage storage;
    private final QuestSettings settings;
    private final QuestSettingsPersistence persistence;
    private final String build;
    private boolean retryOnWorldSave;

    public QuestSettingsLifecycle(WorldStorage storage, QuestSettings settings, String build) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.persistence = new QuestSettingsPersistence(
            settings,
            new JsonDocumentStore(storage));
        this.build = build;
    }

    public JsonDocumentStore.Outcome onServerStarted() throws IOException {
        return persistence.load();
    }

    public void onWorldSave() throws IOException {
        onWorldSave(false);
    }

    /** Saves a world, or suppresses the callback when vanilla is deleting the world. */
    public void onWorldSave(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted) {
            retryOnWorldSave = false;
            settings.reset();
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
            settings.reset();
        }
    }

    public void onServerStopping() throws IOException {
        onServerStopping(false);
    }

    /** Saves and flushes a normal stop, or only cleans up when vanilla is deleting the world. */
    public void onServerStopping(boolean worldBeingDeleted) throws IOException {
        if (worldBeingDeleted || persistence.isWritesDisabled()) {
            retryOnWorldSave = false;
            settings.reset();
            return;
        }

        try {
            persistence.save(build);
        } catch (IOException | RuntimeException failure) {
            // The saveAllWorlds RETURN hook will retry before the lifecycle is discarded.
            retryOnWorldSave = true;
            throw failure;
        }

        try {
            storage.flush();
        } finally {
            retryOnWorldSave = false;
            settings.reset();
        }
    }

    public boolean isRetryOnWorldSave() {
        return retryOnWorldSave;
    }
}
