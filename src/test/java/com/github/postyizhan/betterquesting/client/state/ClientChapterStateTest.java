package com.github.postyizhan.betterquesting.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.enums.EnumQuestVisibility;
import com.github.postyizhan.betterquesting.network.sync.LoginChapterSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientChapterStateTest {
    private static final UUID CHAPTER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID QUEST_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void owningLeasePublishesOneImmutableSnapshotIdempotentlyAndClearsIt() {
        ClientChapterState state = new ClientChapterState();
        ClientChapterState.ConnectionLease lease = state.openConnectionLease();
        LoginChapterSnapshot snapshot = snapshot("Chapter");

        lease.publish(snapshot);
        lease.publish(snapshot("Chapter"));

        assertSame(snapshot, state.current().orElseThrow());
        assertThrows(IllegalStateException.class, () -> lease.publish(snapshot("Conflict")));
        assertSame(snapshot, state.current().orElseThrow());
        lease.close();
        lease.close();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void rebindClearsOldChapterAndStaleCallbacksCannotTouchTheReplacement() {
        ClientChapterState state = new ClientChapterState();
        ClientChapterState.ConnectionLease oldLease = state.openConnectionLease();
        oldLease.publish(snapshot("Old"));

        ClientChapterState.ConnectionLease replacementLease = state.openConnectionLease();
        assertTrue(state.current().isEmpty());
        LoginChapterSnapshot replacement = snapshot("Replacement");
        replacementLease.publish(replacement);

        assertThrows(IllegalStateException.class, () -> oldLease.publish(snapshot("Delayed")));
        oldLease.close();
        assertSame(replacement, state.current().orElseThrow());
        assertEquals("Replacement", state.current().orElseThrow().chapters().get(0).name());
        replacementLease.close();
        assertTrue(state.current().isEmpty());
    }

    private static LoginChapterSnapshot snapshot(String name) {
        return new LoginChapterSnapshot(List.of(new LoginChapterSnapshot.Chapter(
            CHAPTER_ID,
            name,
            "Description",
            EnumQuestVisibility.NORMAL,
            "betterquesting:textures/gui/chapter.png",
            256,
            List.of(new LoginChapterSnapshot.Node(QUEST_ID, -4, 8, 24, 32)))));
    }
}
