package com.github.postyizhan.betterquesting.client.state;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.sync.LoginSettingsSnapshot;
import org.junit.jupiter.api.Test;

class ClientQuestSettingsStateTest {
    @Test
    void owningLeasePublishesTheCompleteImmutableSnapshotAndClearsIt() {
        ClientQuestSettingsState state = new ClientQuestSettingsState();
        ClientQuestSettingsState.ConnectionLease lease = state.openConnectionLease();
        LoginSettingsSnapshot snapshot = snapshot("published", 7);

        assertTrue(state.current().isEmpty());
        lease.publish(snapshot);
        lease.publish(snapshot);

        assertSame(snapshot, state.current().orElseThrow());
        lease.close();
        lease.close();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void delayedOldLeaseCannotOverwriteOrClearANewerPublication() {
        ClientQuestSettingsState state = new ClientQuestSettingsState();
        ClientQuestSettingsState.ConnectionLease oldLease = state.openConnectionLease();
        oldLease.publish(snapshot("old", 1));
        ClientQuestSettingsState.ConnectionLease newLease = state.openConnectionLease();
        LoginSettingsSnapshot replacement = snapshot("new", 2);
        newLease.publish(replacement);

        assertThrows(IllegalStateException.class,
            () -> oldLease.publish(snapshot("stale", 3)));
        oldLease.close();

        assertSame(replacement, state.current().orElseThrow());
        newLease.close();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void closingANonOwningLeaseDoesNotClearAnotherLeasePublication() {
        ClientQuestSettingsState state = new ClientQuestSettingsState();
        ClientQuestSettingsState.ConnectionLease owner = state.openConnectionLease();
        LoginSettingsSnapshot published = snapshot("owner", 4);
        owner.publish(published);
        ClientQuestSettingsState.ConnectionLease unpublished = state.openConnectionLease();

        unpublished.close();

        assertSame(published, state.current().orElseThrow());
        owner.close();
        assertTrue(state.current().isEmpty());
    }

    private static LoginSettingsSnapshot snapshot(String packName, int packVersion) {
        return new LoginSettingsSnapshot(
            packName,
            packVersion,
            true,
            true,
            true,
            5,
            12,
            "betterquesting:textures/gui/test.png",
            0.25F,
            0.75F,
            -31,
            47);
    }
}
