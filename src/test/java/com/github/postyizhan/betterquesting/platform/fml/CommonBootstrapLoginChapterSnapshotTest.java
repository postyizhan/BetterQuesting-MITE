package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.client.state.ClientChapterState;
import com.github.postyizhan.betterquesting.network.sync.LoginChapterSnapshot;
import com.github.postyizhan.betterquesting.network.sync.LoginChapterSnapshotCodec;
import com.github.postyizhan.betterquesting.questing.QuestLine;
import com.github.postyizhan.betterquesting.questing.QuestLineDatabase;
import com.github.postyizhan.betterquesting.questing.QuestLineEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommonBootstrapLoginChapterSnapshotTest {
    private static final UUID FIRST_CHAPTER =
        UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SECOND_CHAPTER =
        UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID DANGLING_QUEST =
        UUID.fromString("00000000-0000-0000-0000-000000000203");

    @Test
    void captureRequiresTheExactActiveLifecycleServerPlayerAndHandlerBinding() {
        Object owner = new Object();
        Object handler = new Object();
        Object lifecycle = new Object();
        QuestLineDatabase chapters = database();

        Optional<LoginChapterSnapshot> captured = capture(
            owner, handler, owner, handler, owner, lifecycle, chapters);

        assertEquals(List.of(SECOND_CHAPTER, FIRST_CHAPTER), captured.orElseThrow().chapters()
            .stream().map(LoginChapterSnapshot.Chapter::chapterId).toList());
        assertTrue(capture(null, handler, owner, handler, owner, lifecycle, chapters).isEmpty());
        assertTrue(capture(owner, null, owner, handler, owner, lifecycle, chapters).isEmpty());
        assertTrue(capture(owner, handler, new Object(), handler, owner, lifecycle, chapters).isEmpty());
        assertTrue(capture(owner, handler, owner, new Object(), owner, lifecycle, chapters).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, new Object(), lifecycle, chapters).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, null, chapters).isEmpty());
        assertTrue(capture(owner, handler, owner, handler, owner, lifecycle, null).isEmpty());
    }

    @Test
    void captureRetainsDanglingQuestIdsAndClientLifecycleNeverMutatesTheServerDatabase() {
        Object owner = new Object();
        Object handler = new Object();
        QuestLineDatabase serverDatabase = database();
        QuestLine second = (QuestLine) serverDatabase.get(SECOND_CHAPTER);
        LoginChapterSnapshot serverSnapshot = capture(
            owner, handler, owner, handler, owner, new Object(), serverDatabase).orElseThrow();
        byte[] wire = LoginChapterSnapshotCodec.encode(serverSnapshot);
        LoginChapterSnapshot detached = LoginChapterSnapshotCodec.decode(wire).orElseThrow();
        ClientChapterState state = new ClientChapterState();
        ClientChapterState.ConnectionLease oldLease = state.openConnectionLease();

        oldLease.publish(detached);
        assertEquals(DANGLING_QUEST,
            state.current().orElseThrow().chapters().get(0).nodes().get(0).questId());
        assertSame(second, serverDatabase.get(SECOND_CHAPTER));
        assertEquals(1, second.size());
        assertEquals(-17, second.get(DANGLING_QUEST).getPosX());

        ClientChapterState.ConnectionLease replacement = state.openConnectionLease();
        assertTrue(state.current().isEmpty());
        replacement.publish(detached);
        oldLease.close();
        replacement.close();

        assertTrue(state.current().isEmpty());
        assertSame(second, serverDatabase.get(SECOND_CHAPTER));
        assertEquals(1, second.size());
        assertEquals(-17, second.get(DANGLING_QUEST).getPosX());
    }

    private static QuestLineDatabase database() {
        QuestLine first = new QuestLine();
        first.setProperty(NativeProps.NAME, "First");
        QuestLine second = new QuestLine();
        second.setProperty(NativeProps.NAME, "Second");
        second.setProperty(NativeProps.DESC, "Server-authored description");
        second.setProperty(NativeProps.VISIBILITY, EnumQuestVisibility.SECRET);
        second.setProperty(NativeProps.BG_IMAGE, "betterquesting:textures/gui/server.png");
        second.setProperty(NativeProps.BG_SIZE, 512);
        second.put(DANGLING_QUEST, new QuestLineEntry(-17, 23, 31, 47));

        QuestLineDatabase database = new QuestLineDatabase();
        database.put(FIRST_CHAPTER, first);
        database.put(SECOND_CHAPTER, second);
        database.setOrderIndex(SECOND_CHAPTER, 0);
        database.setOrderIndex(FIRST_CHAPTER, 1);
        return database;
    }

    private static Optional<LoginChapterSnapshot> capture(
        Object serverOwner,
        Object handler,
        Object playerServer,
        Object playerHandler,
        Object databaseServer,
        Object databaseLifecycle,
        QuestLineDatabase chapters
    ) {
        return CommonBootstrap.captureLoginChapterSnapshot(
            serverOwner,
            handler,
            playerServer,
            playerHandler,
            databaseServer,
            databaseLifecycle,
            chapters);
    }
}
