package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.NBTTagCompound;

/** Persistence boundary for the upstream-compatible world life-database document. */
public final class LifeDatabasePersistence {
    public static final String PATH = "LifeDatabase.json";

    private final LifeDatabase lives;
    private final JsonDocumentStore store;
    private boolean writesDisabled;

    public LifeDatabasePersistence(LifeDatabase lives, JsonDocumentStore store) {
        this.lives = Objects.requireNonNull(lives, "lives");
        this.store = Objects.requireNonNull(store, "store");
    }

    public synchronized JsonDocumentStore.Outcome load() throws IOException {
        writesDisabled = true;
        lives.reset();
        JsonDocumentStore.LoadResult result;
        try {
            result = store.load(PATH, true);
        } catch (IOException | RuntimeException failure) {
            lives.reset();
            throw failure;
        }
        if (result.outcome() != JsonDocumentStore.Outcome.LOADED) {
            writesDisabled = result.outcome() != JsonDocumentStore.Outcome.ABSENT;
            return result.outcome();
        }
        if (!JsonSchemaFields.isCompatibleMitePortFormat(result.root()) || !validRoot(result.root())) {
            store.quarantine(PATH);
            return JsonDocumentStore.Outcome.QUARANTINED;
        }

        LifeDatabase loaded = new LifeDatabase();
        try {
            loaded.readFromNBT(result.root().getCompoundTag("lifeDatabase"), false);
            lives.readFromNBT(loaded.writeToNBT(new NBTTagCompound(), null), false);
        } catch (RuntimeException failure) {
            lives.reset();
            throw failure;
        }
        writesDisabled = false;
        return result.outcome();
    }

    public synchronized void save(String build) throws IOException {
        if (writesDisabled) return;
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("lifeDatabase", lives.writeToNBT(new NBTTagCompound(), null));
        JsonSchemaFields.stamp(root, build);
        store.save(PATH, root, true);
    }

    public synchronized boolean isWritesDisabled() {
        return writesDisabled;
    }

    private static boolean validRoot(NBTTagCompound root) {
        return root.hasKey("lifeDatabase") && NbtCompat.getTagId(root, "lifeDatabase") == 10;
    }
}
