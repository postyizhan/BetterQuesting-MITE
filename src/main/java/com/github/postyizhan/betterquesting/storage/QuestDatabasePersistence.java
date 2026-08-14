package com.github.postyizhan.betterquesting.storage;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import com.github.postyizhan.betterquesting.core.storage.json.JsonSchemaFields;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.questing.QuestDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/**
 * Persistence boundary for the shared world-scoped quest and quest-line document.
 *
 * <p>The upstream document also contains {@code questSettings}. This port does not interpret that
 * root here because {@link QuestSettingsPersistence} owns the active settings lifecycle. When a
 * compatible database document already contains it, this boundary retains an opaque copy and
 * includes that copy in later database saves. It neither creates nor updates embedded settings.
 */
public final class QuestDatabasePersistence {
    public static final String PATH = "QuestDatabase.json";

    private final QuestDatabase quests;
    private final QuestLineDatabase questLines;
    private final JsonDocumentStore store;
    private NBTBase preservedEmbeddedQuestSettings;
    private boolean writesDisabled;

    public QuestDatabasePersistence(
        QuestDatabase quests,
        QuestLineDatabase questLines,
        JsonDocumentStore store
    ) {
        this.quests = Objects.requireNonNull(quests, "quests");
        this.questLines = Objects.requireNonNull(questLines, "questLines");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Loads both databases as one unit, clearing both whenever the document cannot be applied. */
    public synchronized JsonDocumentStore.Outcome load() throws IOException {
        writesDisabled = true;
        preservedEmbeddedQuestSettings = null;
        clearDatabases();
        JsonDocumentStore.LoadResult result;
        try {
            result = store.load(PATH, true);
        } catch (IOException | RuntimeException failure) {
            clearDatabases();
            throw failure;
        }

        if (result.outcome() != JsonDocumentStore.Outcome.LOADED) {
            clearDatabases();
            writesDisabled = result.outcome() != JsonDocumentStore.Outcome.ABSENT;
            return result.outcome();
        }
        if (!JsonSchemaFields.isCompatibleMitePortFormat(result.root())) {
            clearDatabases();
            writesDisabled = true;
            store.quarantine(PATH);
            return JsonDocumentStore.Outcome.QUARANTINED;
        }

        if (!validDatabaseRoot(result.root())) {
            clearDatabases();
            writesDisabled = true;
            store.quarantine(PATH);
            return JsonDocumentStore.Outcome.QUARANTINED;
        }

        QuestDatabase loadedQuests = new QuestDatabase();
        QuestLineDatabase loadedQuestLines = new QuestLineDatabase();
        NBTBase loadedEmbeddedQuestSettings = result.root().hasKey("questSettings")
            ? result.root().getTag("questSettings").copy()
            : null;
        try {
            loadedQuests.readFromNBT(NbtCompat.getListOrEmpty(result.root(), "questDatabase"), false);
            loadedQuestLines.readFromNBT(NbtCompat.getListOrEmpty(result.root(), "questLines"), false);
            replaceDatabases(loadedQuests, loadedQuestLines);
        } catch (RuntimeException failure) {
            clearDatabases();
            throw failure;
        }
        preservedEmbeddedQuestSettings = loadedEmbeddedQuestSettings;
        writesDisabled = false;
        return result.outcome();
    }

    /** Saves both database roots through one atomic, readback-validated replacement. */
    public synchronized void save(String build) throws IOException {
        if (writesDisabled) {
            return;
        }
        NBTTagCompound root = new NBTTagCompound();
        synchronized (quests) {
            synchronized (questLines) {
                root.setTag("questDatabase", quests.writeToNBT(new NBTTagList(), null));
                root.setTag("questLines", questLines.writeToNBT(new NBTTagList(), null));
            }
        }
        if (preservedEmbeddedQuestSettings != null) {
            root.setTag("questSettings", preservedEmbeddedQuestSettings.copy());
        }
        JsonSchemaFields.stamp(root, build);
        store.save(PATH, root, true);
    }

    public synchronized boolean isWritesDisabled() {
        return writesDisabled;
    }

    public void clearDatabases() {
        synchronized (quests) {
            synchronized (questLines) {
                quests.clear();
                questLines.clear();
            }
        }
    }

    private boolean validDatabaseRoot(NBTTagCompound root) {
        return root.hasKey("questDatabase")
            && root.hasKey("questLines")
            && NbtCompat.getTagId(root, "questDatabase") == 9
            && NbtCompat.getTagId(root, "questLines") == 9;
    }

    private void replaceDatabases(QuestDatabase loadedQuests, QuestLineDatabase loadedQuestLines) {
        synchronized (quests) {
            synchronized (questLines) {
                quests.clear();
                quests.putAll(loadedQuests);
                questLines.setOrderedEntries(loadedQuestLines.getOrderedEntries());
            }
        }
    }
}
