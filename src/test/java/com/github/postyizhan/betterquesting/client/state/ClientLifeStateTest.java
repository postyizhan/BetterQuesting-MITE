package com.github.postyizhan.betterquesting.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.sync.LoginLifeSnapshot;
import org.junit.jupiter.api.Test;

class ClientLifeStateTest {
    @Test
    void owningLeasePublishesExactLifeIdempotentlyAndClearsExactlyOnce() {
        ClientLifeState state = new ClientLifeState();
        ClientLifeState.ConnectionLease lease = state.openConnectionLease();
        LoginLifeSnapshot snapshot = new LoginLifeSnapshot(Integer.MIN_VALUE);

        lease.publish(snapshot);
        lease.publish(new LoginLifeSnapshot(Integer.MIN_VALUE));

        assertEquals(Integer.MIN_VALUE, state.current().orElseThrow().lives());
        assertThrows(IllegalStateException.class,
            () -> lease.publish(new LoginLifeSnapshot(Integer.MAX_VALUE)));
        assertSame(snapshot, state.current().orElseThrow());
        lease.close();
        lease.close();
        assertTrue(state.current().isEmpty());
    }

    @Test
    void rebindClearsOldLifeAndStalePublicationOrTeardownCannotTouchNewLife() {
        ClientLifeState state = new ClientLifeState();
        ClientLifeState.ConnectionLease oldLease = state.openConnectionLease();
        oldLease.publish(new LoginLifeSnapshot(3));

        ClientLifeState.ConnectionLease newLease = state.openConnectionLease();
        assertTrue(state.current().isEmpty());
        LoginLifeSnapshot replacement = new LoginLifeSnapshot(-7);
        newLease.publish(replacement);

        assertThrows(IllegalStateException.class,
            () -> oldLease.publish(new LoginLifeSnapshot(99)));
        oldLease.close();
        assertSame(replacement, state.current().orElseThrow());
        newLease.close();
        assertTrue(state.current().isEmpty());
    }
}
