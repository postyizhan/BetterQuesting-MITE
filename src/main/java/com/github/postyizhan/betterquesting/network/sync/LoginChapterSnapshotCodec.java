package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import com.github.postyizhan.betterquesting.network.BoundedNbtWireCodec;
import com.github.postyizhan.betterquesting.network.NbtLimits;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.NBTBase;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;

/** Exact-schema bounded NBT codec for the server-authored login chapter snapshot. */
public final class LoginChapterSnapshotCodec {
    private static final String CHAPTERS = "chapters";
    private static final String CHAPTER_ID = "chapterId";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String VISIBILITY = "visibility";
    private static final String BACKGROUND_IMAGE = "backgroundImage";
    private static final String BACKGROUND_SIZE = "backgroundSize";
    private static final String NODES = "nodes";
    private static final String QUEST_ID = "questId";
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT = 3;

    public static final int MIN_ENCODED_BYTES = 20;
    public static final int MAX_ENCODED_BYTES = 8_388_544;
    private static final NbtLimits LIMITS = new NbtLimits(
        6,
        425_986,
        7,
        65_536,
        LoginChapterSnapshot.MAX_DESCRIPTION_UTF16,
        0,
        0,
        MAX_ENCODED_BYTES);

    private LoginChapterSnapshotCodec() {
    }

    public static byte[] encode(LoginChapterSnapshot snapshot) {
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList chapterList = emptyOrTypedList();
        for (LoginChapterSnapshot.Chapter chapter : snapshot.chapters()) {
            NBTTagCompound serialized = new NBTTagCompound();
            serialized.setString(CHAPTER_ID, chapter.chapterId().toString());
            serialized.setString(NAME, chapter.name());
            serialized.setString(DESCRIPTION, chapter.description());
            serialized.setString(VISIBILITY, chapter.visibility().name());
            serialized.setString(BACKGROUND_IMAGE, chapter.backgroundImage());
            serialized.setInteger(BACKGROUND_SIZE, chapter.backgroundSize());
            NBTTagList nodes = emptyOrTypedList();
            for (LoginChapterSnapshot.Node node : chapter.nodes()) {
                NBTTagCompound serializedNode = new NBTTagCompound();
                serializedNode.setString(QUEST_ID, node.questId().toString());
                serializedNode.setInteger("x", node.x());
                serializedNode.setInteger("y", node.y());
                serializedNode.setInteger("sizeX", node.sizeX());
                serializedNode.setInteger("sizeY", node.sizeY());
                nodes.appendTag(serializedNode);
            }
            serialized.setTag(NODES, nodes);
            chapterList.appendTag(serialized);
        }
        root.setTag(CHAPTERS, chapterList);
        byte[] encoded = BoundedNbtWireCodec.encode(root, LIMITS);
        if (encoded.length < MIN_ENCODED_BYTES || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("login chapter snapshot has an invalid wire size");
        }
        return encoded;
    }

    public static Optional<LoginChapterSnapshot> decode(byte[] encoded) {
        if (encoded == null || encoded.length < MIN_ENCODED_BYTES
            || encoded.length > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }
        Optional<NBTTagCompound> decoded = BoundedNbtWireCodec.decode(encoded, LIMITS);
        if (decoded.isEmpty() || !hasCanonicalListTypes(encoded)) {
            return Optional.empty();
        }
        try {
            NBTTagCompound root = decoded.orElseThrow();
            if (root.getName() == null || !root.getName().isEmpty()
                || root.getTags().size() != 1
                || NbtCompat.getTagId(root, CHAPTERS) != TAG_LIST) {
                return Optional.empty();
            }
            NBTBase chapterTag = root.getTag(CHAPTERS);
            if (!(chapterTag instanceof NBTTagList chapterList)
                || !hasCompoundElementType(chapterList)
                || chapterList.tagCount() > LoginChapterSnapshot.MAX_CHAPTERS) {
                return Optional.empty();
            }

            Set<UUID> chapterIds = new HashSet<>();
            java.util.ArrayList<LoginChapterSnapshot.Chapter> chapters =
                new java.util.ArrayList<>(chapterList.tagCount());
            int totalNodes = 0;
            for (int chapterIndex = 0; chapterIndex < chapterList.tagCount(); chapterIndex++) {
                NBTBase tag = chapterList.tagAt(chapterIndex);
                if (!(tag instanceof NBTTagCompound chapter)
                    || !hasExactFields(chapter, 7,
                        CHAPTER_ID, NAME, DESCRIPTION, VISIBILITY,
                        BACKGROUND_IMAGE, BACKGROUND_SIZE, NODES)
                    || NbtCompat.getTagId(chapter, CHAPTER_ID) != TAG_STRING
                    || NbtCompat.getTagId(chapter, NAME) != TAG_STRING
                    || NbtCompat.getTagId(chapter, DESCRIPTION) != TAG_STRING
                    || NbtCompat.getTagId(chapter, VISIBILITY) != TAG_STRING
                    || NbtCompat.getTagId(chapter, BACKGROUND_IMAGE) != TAG_STRING
                    || NbtCompat.getTagId(chapter, BACKGROUND_SIZE) != TAG_INT
                    || NbtCompat.getTagId(chapter, NODES) != TAG_LIST) {
                    return Optional.empty();
                }
                UUID chapterId = canonicalUuid(chapter.getString(CHAPTER_ID));
                if (!chapterIds.add(chapterId)) {
                    return Optional.empty();
                }
                EnumQuestVisibility visibility;
                try {
                    visibility = EnumQuestVisibility.valueOf(chapter.getString(VISIBILITY));
                } catch (IllegalArgumentException invalidVisibility) {
                    return Optional.empty();
                }
                NBTBase nodeTag = chapter.getTag(NODES);
                if (!(nodeTag instanceof NBTTagList nodeList)
                    || !hasCompoundElementType(nodeList)
                    || nodeList.tagCount() > LoginChapterSnapshot.MAX_NODES_PER_CHAPTER) {
                    return Optional.empty();
                }
                java.util.ArrayList<LoginChapterSnapshot.Node> nodes =
                    new java.util.ArrayList<>(nodeList.tagCount());
                UUID previousQuest = null;
                for (int nodeIndex = 0; nodeIndex < nodeList.tagCount(); nodeIndex++) {
                    NBTBase rawNode = nodeList.tagAt(nodeIndex);
                    if (!(rawNode instanceof NBTTagCompound node)
                        || !hasExactFields(node, 5, QUEST_ID, "x", "y", "sizeX", "sizeY")
                        || NbtCompat.getTagId(node, QUEST_ID) != TAG_STRING
                        || NbtCompat.getTagId(node, "x") != TAG_INT
                        || NbtCompat.getTagId(node, "y") != TAG_INT
                        || NbtCompat.getTagId(node, "sizeX") != TAG_INT
                        || NbtCompat.getTagId(node, "sizeY") != TAG_INT) {
                        return Optional.empty();
                    }
                    UUID questId = canonicalUuid(node.getString(QUEST_ID));
                    if (previousQuest != null && previousQuest.compareTo(questId) >= 0) {
                        return Optional.empty();
                    }
                    previousQuest = questId;
                    nodes.add(new LoginChapterSnapshot.Node(
                        questId,
                        node.getInteger("x"),
                        node.getInteger("y"),
                        node.getInteger("sizeX"),
                        node.getInteger("sizeY")));
                }
                totalNodes += nodes.size();
                if (totalNodes > LoginChapterSnapshot.MAX_TOTAL_NODES) {
                    return Optional.empty();
                }
                chapters.add(new LoginChapterSnapshot.Chapter(
                    chapterId,
                    chapter.getString(NAME),
                    chapter.getString(DESCRIPTION),
                    visibility,
                    chapter.getString(BACKGROUND_IMAGE),
                    chapter.getInteger(BACKGROUND_SIZE),
                    nodes));
            }
            return Optional.of(new LoginChapterSnapshot(chapters));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static boolean hasExactFields(NBTTagCompound compound, int count, String... names) {
        if (compound.getName() == null || !compound.getName().isEmpty()
            || compound.getTags().size() != count) {
            return false;
        }
        for (String name : names) {
            if (compound.getTag(name) == null) {
                return false;
            }
        }
        return true;
    }

    private static UUID canonicalUuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw new IllegalArgumentException("UUID is not canonical");
        }
        return id;
    }

    private static boolean hasCompoundElementType(NBTTagList list) {
        return list.tagCount() == 0 || list.tagAt(0).getId() == TAG_COMPOUND;
    }

    private static boolean hasCanonicalListTypes(byte[] encoded) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (readTagId(input) != TAG_COMPOUND) {
                return false;
            }
            input.readUTF();
            readPayload(input, TAG_COMPOUND);
            return input.available() == 0;
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    private static void readPayload(DataInputStream input, int type) throws IOException {
        switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> input.skipNBytes(input.readInt());
            case TAG_STRING -> input.readUTF();
            case TAG_LIST -> {
                int elementType = readTagId(input);
                int count = input.readInt();
                if ((count == 0 && elementType != 1)
                    || (count > 0 && elementType != TAG_COMPOUND)) {
                    throw new IOException("login chapter list has a non-canonical element type");
                }
                for (int index = 0; index < count; index++) {
                    readPayload(input, elementType);
                }
            }
            case TAG_COMPOUND -> {
                int childType;
                while ((childType = readTagId(input)) != 0) {
                    input.readUTF();
                    readPayload(input, childType);
                }
            }
            case 11 -> input.skipNBytes(Math.multiplyExact(input.readInt(), Integer.BYTES));
            default -> throw new IOException("unsupported login chapter NBT tag type");
        }
    }

    private static int readTagId(DataInputStream input) throws IOException {
        int diskId = input.readByte();
        return diskId > 0 ? 128 - diskId : diskId;
    }

    private static NBTTagList emptyOrTypedList() {
        NBTTagList list = new NBTTagList();
        // MITE retains this compound type in memory but writes an empty list with byte type.
        list.appendTag(new NBTTagCompound());
        list.removeTag(0);
        return list;
    }
}
