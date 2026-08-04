package betterquesting.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.apache.commons.io.FileUtils;

import betterquesting.core.BetterQuesting;
import cpw.mods.fml.common.FMLCommonHandler;

public class BookmarkHandler {

    private static final List<String> BOOKMARKS = new ArrayList<>();
    private static final String BOOKMARK_DIR = BetterQuesting.MODID + "/bookmarks/";
    private static File bookmarkFile;
    private static boolean hasChanged = true;

    public static boolean bookmarkQuest(UUID questId) {
        String uuid = questId.toString();
        boolean added = false;
        if (BOOKMARKS.contains(uuid)) {
            BOOKMARKS.remove(uuid);
        } else {
            BOOKMARKS.add(uuid);
            added = true;
        }

        hasChanged = true;
        saveBookmarks();
        return added;
    }

    public static int getIndexOf(UUID questId) {
        return BOOKMARKS.indexOf(questId.toString());
    }

    public static boolean isBookmarked(UUID questId) {
        return BOOKMARKS.contains(questId.toString());
    }

    public static boolean hasChanged() {
        if (hasChanged) {
            hasChanged = false;
            return true;
        }
        return false;
    }

    private static void saveBookmarks() {
        try {
            FileUtils.writeLines(bookmarkFile, BOOKMARKS);
        } catch (IOException ignored) {
            BetterQuesting.logger.warn("Failed to save bookmarks.");
        }
    }

    public static void loadBookmarks(String address) {
        String identifier = getIdentifier(address);
        bookmarkFile = new File(BOOKMARK_DIR, String.format("%s.txt", identifier));
        BOOKMARKS.clear();
        hasChanged = true;

        if (!bookmarkFile.exists()) return;

        try {
            List<String> bookmarks = FileUtils.readLines(bookmarkFile, StandardCharsets.UTF_8);
            BOOKMARKS.addAll(bookmarks);
        } catch (IOException ignored) {
            BetterQuesting.logger.warn("Failed to load bookmarks for {}", identifier);
        }
    }

    private static String getIdentifier(String address) {
        if (Minecraft.getMinecraft()
            .isSingleplayer()) {
            return FMLCommonHandler.instance()
                .getMinecraftServerInstance()
                .getFolderName();
        }

        int index = address.indexOf("/") + 1;
        return address.substring(index)
            .replace(":", ".");
    }
}
