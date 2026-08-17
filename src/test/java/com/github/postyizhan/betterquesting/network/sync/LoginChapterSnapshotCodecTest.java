package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.network.BoundedNbtWireCodec;
import com.github.postyizhan.betterquesting.network.NbtLimits;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.NBTTagCompound;
import net.minecraft.NBTTagList;
import org.junit.jupiter.api.Test;

class LoginChapterSnapshotCodecTest {
    private static final UUID FIRST_CHAPTER =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_CHAPTER =
        UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_QUEST =
        UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SECOND_QUEST =
        UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final NbtLimits WIDE = new NbtLimits(
        16, 1_000, 16, 100, 20_000, 100, 100, 1_000_000L);

    @Test
    void roundTripsImmutableDisplayOrderAndCanonicalUuidNodeOrder() {
        List<LoginChapterSnapshot.Node> mutableNodes = new ArrayList<>();
        mutableNodes.add(new LoginChapterSnapshot.Node(SECOND_QUEST, 2, 3, 4, 5));
        mutableNodes.add(new LoginChapterSnapshot.Node(FIRST_QUEST, -2, -3, -4, -5));
        List<LoginChapterSnapshot.Chapter> mutableChapters = new ArrayList<>();
        mutableChapters.add(chapter(SECOND_CHAPTER, "Second", mutableNodes));
        mutableChapters.add(chapter(FIRST_CHAPTER, "First", List.of()));

        LoginChapterSnapshot snapshot = new LoginChapterSnapshot(mutableChapters);
        mutableNodes.clear();
        mutableChapters.clear();
        byte[] encoded = LoginChapterSnapshotCodec.encode(snapshot);
        LoginChapterSnapshot decoded = LoginChapterSnapshotCodec.decode(encoded).orElseThrow();

        assertEquals(List.of(SECOND_CHAPTER, FIRST_CHAPTER),
            decoded.chapters().stream().map(LoginChapterSnapshot.Chapter::chapterId).toList());
        assertEquals(List.of(FIRST_QUEST, SECOND_QUEST), decoded.chapters().get(0).nodes()
            .stream().map(LoginChapterSnapshot.Node::questId).toList());
        assertEquals(snapshot, decoded);
        assertEquals("betterquesting:login_chapter", snapshot.formatId());
        assertEquals(1, snapshot.formatVersion());
        assertThrows(UnsupportedOperationException.class,
            () -> decoded.chapters().add(chapter(FIRST_CHAPTER, "x", List.of())));
        assertThrows(UnsupportedOperationException.class,
            () -> decoded.chapters().get(0).nodes().clear());
    }

    @Test
    void emptySnapshotHasTheExactMinimumBodyAndAllPublishedBoundsMatchTheAudit() {
        byte[] encoded = LoginChapterSnapshotCodec.encode(new LoginChapterSnapshot(List.of()));

        assertEquals(20, encoded.length);
        assertEquals(20, LoginChapterSnapshotCodec.MIN_ENCODED_BYTES);
        assertEquals(8_388_544, LoginChapterSnapshotCodec.MAX_ENCODED_BYTES);
        assertEquals(4_096, LoginChapterSnapshot.MAX_CHAPTERS);
        assertEquals(16_384, LoginChapterSnapshot.MAX_NODES_PER_CHAPTER);
        assertEquals(65_536, LoginChapterSnapshot.MAX_TOTAL_NODES);
        assertEquals(new LoginChapterSnapshot(List.of()),
            LoginChapterSnapshotCodec.decode(encoded).orElseThrow());
    }

    @Test
    void constructionRejectsDuplicateIdsAndInvalidUtf16WithoutPartialValues() {
        LoginChapterSnapshot.Chapter first = chapter(FIRST_CHAPTER, "First", List.of());
        assertThrows(IllegalArgumentException.class,
            () -> new LoginChapterSnapshot(List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> chapter(FIRST_CHAPTER, "duplicate",
            List.of(
                new LoginChapterSnapshot.Node(FIRST_QUEST, 0, 0, 1, 1),
                new LoginChapterSnapshot.Node(FIRST_QUEST, 1, 1, 2, 2))));
        assertThrows(IllegalArgumentException.class,
            () -> chapter(FIRST_CHAPTER, "x".repeat(257), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginChapterSnapshot.Chapter(
                FIRST_CHAPTER, "name", "x".repeat(16_385), EnumQuestVisibility.NORMAL,
                "", 1, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new LoginChapterSnapshot.Chapter(
                FIRST_CHAPTER, "name", "desc", EnumQuestVisibility.NORMAL,
                "x".repeat(2_049), 1, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> chapter(FIRST_CHAPTER, "bad\ud800", List.of()));
        assertThrows(NullPointerException.class,
            () -> new LoginChapterSnapshot.Node(null, 0, 0, 0, 0));
    }

    @Test
    void rejectsNamedRootMissingExtraWrongTypeInvalidEnumAndNonCanonicalNodeOrder() {
        NBTTagCompound named = root();
        named.setName("login");
        assertRejected(named);

        NBTTagCompound missing = new NBTTagCompound();
        assertRejected(missing);

        NBTTagCompound extra = root();
        extra.setInteger("extra", 1);
        assertRejected(extra);

        NBTTagCompound wrongRootType = new NBTTagCompound();
        wrongRootType.setString("chapters", "wrong");
        assertRejected(wrongRootType);

        byte[] wrongEmptyListType = LoginChapterSnapshotCodec.encode(
            new LoginChapterSnapshot(List.of()));
        wrongEmptyListType[14] = 125;
        assertTrue(LoginChapterSnapshotCodec.decode(wrongEmptyListType).isEmpty());

        NBTTagCompound invalidEnum = root(chapterTag(FIRST_CHAPTER, "BOGUS", List.of()));
        assertRejected(invalidEnum);

        NBTTagCompound wrongNodeType = root(chapterTag(FIRST_CHAPTER, "NORMAL", List.of(
            nodeTag(FIRST_QUEST, 0), nodeTag(SECOND_QUEST, 1))));
        ((NBTTagCompound) ((NBTTagList) ((NBTTagCompound) wrongNodeType.getTagList("chapters")
            .tagAt(0)).getTag("nodes")).tagAt(0)).setString("x", "wrong");
        assertRejected(wrongNodeType);

        NBTTagCompound reversed = root(chapterTag(FIRST_CHAPTER, "NORMAL", List.of(
            nodeTag(SECOND_QUEST, 1), nodeTag(FIRST_QUEST, 0))));
        assertRejected(reversed);
    }

    @Test
    void rejectsDuplicateWireIdsArraysDepthMalformedUtf16TruncationAndTrailingData() {
        assertRejected(root(
            chapterTag(FIRST_CHAPTER, "NORMAL", List.of()),
            chapterTag(FIRST_CHAPTER, "NORMAL", List.of())));
        assertRejected(root(chapterTag(FIRST_CHAPTER, "NORMAL", List.of(
            nodeTag(FIRST_QUEST, 0), nodeTag(FIRST_QUEST, 1)))));

        NBTTagCompound array = new NBTTagCompound();
        array.setByteArray("chapters", new byte[0]);
        assertRejected(array);

        NBTTagCompound invalidUtf16 = root(chapterTag(FIRST_CHAPTER, "NORMAL", List.of()));
        ((NBTTagCompound) invalidUtf16.getTagList("chapters").tagAt(0))
            .setString("name", "bad\ud800");
        assertRejected(invalidUtf16);

        NBTTagCompound depth = root(chapterTag(FIRST_CHAPTER, "NORMAL", List.of()));
        NBTTagCompound nested = new NBTTagCompound();
        nested.setTag("child", depth.getTag("chapters"));
        NBTTagCompound depthRoot = new NBTTagCompound();
        depthRoot.setTag("chapters", nested);
        assertRejected(depthRoot);

        byte[] encoded = LoginChapterSnapshotCodec.encode(new LoginChapterSnapshot(List.of(
            chapter(FIRST_CHAPTER, "Chapter", List.of(
                new LoginChapterSnapshot.Node(FIRST_QUEST, 1, 2, 3, 4))))));
        assertTrue(LoginChapterSnapshotCodec.decode(null).isEmpty());
        for (int length = 0; length < encoded.length; length++) {
            assertTrue(LoginChapterSnapshotCodec.decode(Arrays.copyOf(encoded, length)).isEmpty(),
                "accepted truncation at byte " + length);
        }
        assertTrue(LoginChapterSnapshotCodec.decode(
            Arrays.copyOf(encoded, encoded.length + 1)).isEmpty());
        assertTrue(LoginChapterSnapshotCodec.decode(
            new byte[LoginChapterSnapshotCodec.MAX_ENCODED_BYTES + 1]).isEmpty());
    }

    private static LoginChapterSnapshot.Chapter chapter(
        UUID id,
        String name,
        List<LoginChapterSnapshot.Node> nodes
    ) {
        return new LoginChapterSnapshot.Chapter(
            id, name, "Description", EnumQuestVisibility.NORMAL,
            "betterquesting:textures/gui/chapter.png", 256, nodes);
    }

    private static void assertRejected(NBTTagCompound root) {
        assertTrue(LoginChapterSnapshotCodec.decode(
            BoundedNbtWireCodec.encode(root, WIDE)).isEmpty());
    }

    private static NBTTagCompound root(NBTTagCompound... chapters) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = compoundList();
        for (NBTTagCompound chapter : chapters) {
            list.appendTag(chapter);
        }
        root.setTag("chapters", list);
        return root;
    }

    private static NBTTagCompound chapterTag(UUID id, String visibility, List<NBTTagCompound> nodes) {
        NBTTagCompound chapter = new NBTTagCompound();
        chapter.setString("chapterId", id.toString());
        chapter.setString("name", "Chapter");
        chapter.setString("description", "Description");
        chapter.setString("visibility", visibility);
        chapter.setString("backgroundImage", "background.png");
        chapter.setInteger("backgroundSize", 256);
        NBTTagList nodeList = compoundList();
        for (NBTTagCompound node : nodes) {
            nodeList.appendTag(node);
        }
        chapter.setTag("nodes", nodeList);
        return chapter;
    }

    private static NBTTagCompound nodeTag(UUID id, int coordinate) {
        NBTTagCompound node = new NBTTagCompound();
        node.setString("questId", id.toString());
        node.setInteger("x", coordinate);
        node.setInteger("y", coordinate);
        node.setInteger("sizeX", coordinate);
        node.setInteger("sizeY", coordinate);
        return node;
    }

    private static NBTTagList compoundList() {
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagCompound());
        list.removeTag(0);
        return list;
    }
}
