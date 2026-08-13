package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.NBTTagCompound;

/**
 * The production JSON boundary for the world-scoped QuestSettings document.
 *
 * <p>This class deliberately owns no platform or lifecycle state. The server lifecycle supplies a
 * world-bound {@link JsonDocumentStore}; pure-JVM tests can therefore exercise missing-file,
 * quarantine, atomic-write, and readback behaviour without constructing a Minecraft server.
 */
public final class QuestSettingsPersistence {
    public static final String PATH = "QuestSettings.json";

    private final QuestSettings settings;
    private final JsonDocumentStore store;

    public QuestSettingsPersistence(QuestSettings settings, JsonDocumentStore store) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Loads settings, restoring the existing upstream defaults when the file is absent or bad. */
    public JsonDocumentStore.Outcome load() throws IOException {
        JsonDocumentStore.LoadResult result = store.load(PATH, true);
        if (result.outcome() == JsonDocumentStore.Outcome.LOADED) {
            settings.readFromNBT(result.root());
        } else {
            settings.reset();
        }
        return result.outcome();
    }

    /** Atomically saves the current settings and validates the temporary JSON by reading it back. */
    public void save(String build) throws IOException {
        NBTTagCompound root = settings.writeToNBT(new NBTTagCompound());
        JsonSchemaFields.stamp(root, build);
        store.save(PATH, root, true);
    }
}
