package com.github.postyizhan.betterquesting.client.state;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.sync.LoginNameSnapshot;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPlayerNameStateTest {
    private static final UUID PLAYER_ID =
        UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void owningLeasePublishesExactSnapshotIdempotentlyAndConflictsFailClosed() {
        ClientPlayerNameState state = new ClientPlayerNameState();
        ClientPlayerNameState.ConnectionLease lease = state.openConnectionLease();
        LoginNameSnapshot snapshot = new LoginNameSnapshot(PLAYER_ID, "Alice");

        lease.publish(snapshot);
        lease.publish(new LoginNameSnapshot(PLAYER_ID, "Alice"));

        assertSame(snapshot, state.current().orElseThrow());
        assertThrows(IllegalStateException.class,
            () -> lease.publish(new LoginNameSnapshot(PLAYER_ID, "Bob")));
        assertSame(snapshot, state.current().orElseThrow());
        lease.close();
        lease.close();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void newLeaseClearsOldNameAndDelayedPublicationOrCloseCannotTouchReplacement() {
        ClientPlayerNameState state = new ClientPlayerNameState();
        ClientPlayerNameState.ConnectionLease oldLease = state.openConnectionLease();
        oldLease.publish(new LoginNameSnapshot(PLAYER_ID, "Alice"));

        ClientPlayerNameState.ConnectionLease replacementLease = state.openConnectionLease();
        assertTrue(state.current().isEmpty());
        LoginNameSnapshot replacement = new LoginNameSnapshot(PLAYER_ID, "ALICE");
        replacementLease.publish(replacement);

        assertThrows(IllegalStateException.class,
            () -> oldLease.publish(new LoginNameSnapshot(PLAYER_ID, "Delayed")));
        oldLease.close();
        assertSame(replacement, state.current().orElseThrow());
        replacementLease.close();
        assertTrue(state.current().isEmpty());
    }
}
