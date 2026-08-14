package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/** Persistence boundary for the upstream-compatible world name-cache document. */
public final class NameCachePersistence {
    public static final String PATH = "NameCache.json";

    private final NameCache names;
    private final JsonDocumentStore store;
    private boolean writesDisabled;

    public NameCachePersistence(NameCache names, JsonDocumentStore store) {
        this.names = Objects.requireNonNull(names, "names");
        this.store = Objects.requireNonNull(store, "store");
    }

    public synchronized JsonDocumentStore.Outcome load() throws IOException {
        writesDisabled = true;
        names.reset();
        JsonDocumentStore.LoadResult result;
        try {
            result = store.load(PATH, true);
        } catch (IOException | RuntimeException failure) {
            names.reset();
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

        NameCache loaded = new NameCache();
        try {
            loaded.readFromNBT(NbtCompat.getListOrEmpty(result.root(), "nameCache"), false);
            names.readFromNBT(loaded.writeToNBT(new NBTTagList(), null), false);
        } catch (RuntimeException failure) {
            names.reset();
            throw failure;
        }
        writesDisabled = false;
        return result.outcome();
    }

    public synchronized void save(String build) throws IOException {
        if (writesDisabled) return;
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("nameCache", names.writeToNBT(new NBTTagList(), null));
        JsonSchemaFields.stamp(root, build);
        store.save(PATH, root, true);
    }

    public synchronized boolean isWritesDisabled() {
        return writesDisabled;
    }

    private static boolean validRoot(NBTTagCompound root) {
        return root.hasKey("nameCache") && NbtCompat.getTagId(root, "nameCache") == 9;
    }
}
