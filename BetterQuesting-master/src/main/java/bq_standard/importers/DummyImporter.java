package bq_standard.importers;

import java.io.File;
import java.io.FileFilter;
import java.util.HashMap;

import net.minecraft.nbt.NBTTagList;

import betterquesting.api.client.importers.IImporter;
import betterquesting.api.questing.IQuestDatabase;
import betterquesting.api.questing.IQuestLineDatabase;
import betterquesting.api.utils.FileExtensionFilter;

/**
 * Dummy importer that doesn't actually do anything.
 *
 * <p>
 * Mostly just here to keep the package directory around. We should probably just remove the
 * importer feature entirely if we don't use it.
 */
public class DummyImporter implements IImporter {

    public static final DummyImporter INSTANCE = new DummyImporter();
    private static final FileFilter FILTER = new FileExtensionFilter(".json");

    @Override
    public String getUnlocalisedName() {
        return "bq_standard.importer.dummy.name";
    }

    @Override
    public String getUnlocalisedDescription() {
        return "bq_standard.importer.dummy.desc";
    }

    @Override
    public FileFilter getFileFilter() {
        return FILTER;
    }

    @Override
    public void loadFiles(IQuestDatabase questDB, IQuestLineDatabase lineDB, File[] files) {
        throw new UnsupportedOperationException("Dummy importer!");
    }

    private HashMap<Integer, Integer> readQuests(NBTTagList json, IQuestDatabase questDB) {
        throw new UnsupportedOperationException("Dummy importer!");
    }

    private void readQuestLines(NBTTagList json, IQuestLineDatabase lineDB, HashMap<Integer, Integer> remappeIDs) {
        throw new UnsupportedOperationException("Dummy importer!");
    }
}
