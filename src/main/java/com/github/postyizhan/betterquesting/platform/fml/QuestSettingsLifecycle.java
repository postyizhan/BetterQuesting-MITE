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
    private final QuestSettingsPersistence persistence;
    private final String build;

    public QuestSettingsLifecycle(WorldStorage storage, QuestSettings settings, String build) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.persistence = new QuestSettingsPersistence(
            Objects.requireNonNull(settings, "settings"),
            new JsonDocumentStore(storage));
        this.build = build;
    }

    public JsonDocumentStore.Outcome onServerStarted() throws IOException {
        return persistence.load();
    }

    public void onWorldSave() throws IOException {
        persistence.save(build);
    }

    public void onServerStopping() throws IOException {
        persistence.save(build);
        storage.flush();
    }
}
