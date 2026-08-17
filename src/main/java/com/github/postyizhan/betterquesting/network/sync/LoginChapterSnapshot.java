package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.api.questing.IQuestLine;
import com.github.postyizhan.betterquesting.api.questing.IQuestLineEntry;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable server-authored chapter layout captured for one login transfer. */
public record LoginChapterSnapshot(List<LoginChapterSnapshot.Chapter> chapters) {
    public static final String FORMAT_ID = "betterquesting:login_chapter";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_CHAPTERS = 4_096;
    public static final int MAX_NODES_PER_CHAPTER = 16_384;
    public static final int MAX_TOTAL_NODES = 65_536;
    public static final int MAX_NAME_UTF16 = 256;
    public static final int MAX_DESCRIPTION_UTF16 = 16_384;
    public static final int MAX_BACKGROUND_UTF16 = 2_048;

    public LoginChapterSnapshot {
        Objects.requireNonNull(chapters, "chapters");
        if (chapters.size() > MAX_CHAPTERS) {
            throw new IllegalArgumentException("chapter count exceeds the login bound");
        }
        List<Chapter> detached = new ArrayList<>(chapters.size());
        Set<UUID> chapterIds = new HashSet<>();
        int totalNodes = 0;
        for (Chapter chapter : chapters) {
            Chapter value = Objects.requireNonNull(chapter, "chapter");
            if (!chapterIds.add(value.chapterId())) {
                throw new IllegalArgumentException("duplicate chapter UUID");
            }
            totalNodes = Math.addExact(totalNodes, value.nodes().size());
            if (totalNodes > MAX_TOTAL_NODES) {
                throw new IllegalArgumentException("total chapter node count exceeds the login bound");
            }
            detached.add(value);
        }
        chapters = List.copyOf(detached);
    }

    public static LoginChapterSnapshot capture(QuestLineDatabase database) {
        Objects.requireNonNull(database, "database");
        List<Chapter> captured = new ArrayList<>();
        for (Map.Entry<UUID, IQuestLine> chapterEntry : database.getOrderedEntries()) {
            UUID chapterId = chapterEntry.getKey();
            IQuestLine line = chapterEntry.getValue();
            if (chapterId == null || line == null) {
                throw new IllegalArgumentException("active quest-line database contains a null chapter");
            }
            List<Node> nodes = new ArrayList<>();
            for (Map.Entry<UUID, IQuestLineEntry> nodeEntry : line.entrySet()) {
                if (nodeEntry.getKey() == null || nodeEntry.getValue() == null) {
                    throw new IllegalArgumentException("active quest-line contains a null quest node");
                }
                IQuestLineEntry node = nodeEntry.getValue();
                nodes.add(new Node(
                    nodeEntry.getKey(),
                    node.getPosX(),
                    node.getPosY(),
                    node.getSizeX(),
                    node.getSizeY()));
            }
            captured.add(new Chapter(
                chapterId,
                line.getProperty(NativeProps.NAME, "New Quest Line"),
                line.getProperty(NativeProps.DESC, "No Description"),
                line.getProperty(NativeProps.VISIBILITY, EnumQuestVisibility.NORMAL),
                line.getProperty(NativeProps.BG_IMAGE, ""),
                line.getProperty(NativeProps.BG_SIZE, 256),
                nodes));
        }
        return new LoginChapterSnapshot(captured);
    }

    public String formatId() {
        return FORMAT_ID;
    }

    public int formatVersion() {
        return FORMAT_VERSION;
    }

    public record Chapter(
        UUID chapterId,
        String name,
        String description,
        EnumQuestVisibility visibility,
        String backgroundImage,
        int backgroundSize,
        List<Node> nodes
    ) {
        public Chapter {
            Objects.requireNonNull(chapterId, "chapterId");
            validateText(name, MAX_NAME_UTF16, "name");
            validateText(description, MAX_DESCRIPTION_UTF16, "description");
            Objects.requireNonNull(visibility, "visibility");
            validateText(backgroundImage, MAX_BACKGROUND_UTF16, "backgroundImage");
            Objects.requireNonNull(nodes, "nodes");
            if (nodes.size() > MAX_NODES_PER_CHAPTER) {
                throw new IllegalArgumentException("chapter node count exceeds the login bound");
            }
            List<Node> canonical = new ArrayList<>(nodes.size());
            Set<UUID> questIds = new HashSet<>();
            for (Node node : nodes) {
                Node value = Objects.requireNonNull(node, "node");
                if (!questIds.add(value.questId())) {
                    throw new IllegalArgumentException("duplicate quest UUID in chapter");
                }
                canonical.add(value);
            }
            canonical.sort(Comparator.comparing(Node::questId));
            nodes = List.copyOf(canonical);
        }
    }

    public record Node(UUID questId, int x, int y, int sizeX, int sizeY) {
        public Node {
            Objects.requireNonNull(questId, "questId");
        }
    }

    private static void validateText(String value, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds the UTF-16 bound");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
    }
}
