package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LoginSettingsSyncStateTest {
    @Test
    void publishesOneFullyDecodedSnapshotAndRejectsDuplicateOrConflict() {
        List<LoginSettingsSnapshot> applications = new ArrayList<>();
        LoginSettingsSyncState state = new LoginSettingsSyncState(applications::add);
        LoginSettingsSnapshot first = snapshot("First", 1);
        LoginSettingsSnapshot conflicting = snapshot("Second", 2);

        assertEquals(LoginSettingsSyncState.ApplyOutcome.APPLIED,
            state.applyEncoded(LoginSettingsSnapshotCodec.encode(first)));
        assertEquals(LoginSettingsSyncState.State.PUBLISHED, state.state());
        assertEquals(first, state.snapshot().orElseThrow());

        assertEquals(LoginSettingsSyncState.ApplyOutcome.DUPLICATE, state.apply(first));
        assertEquals(LoginSettingsSyncState.ApplyOutcome.CONFLICT, state.apply(conflicting));
        assertEquals(LoginSettingsSyncState.ApplyOutcome.INVALID, state.applyEncoded(new byte[]{1}));
        assertEquals(first, state.snapshot().orElseThrow());
        assertEquals(List.of(first), applications);
    }

    @Test
    void invalidAndOverLimitApplicationsPublishNothingAndCanRetry() {
        List<LoginSettingsSnapshot> applications = new ArrayList<>();
        LoginSettingsSyncState state = new LoginSettingsSyncState(applications::add);
        byte[] invalidVersion = LoginSettingsSnapshotCodec.encode(snapshot("Valid", 1));
        invalidVersion[4] = 2;

        assertEquals(LoginSettingsSyncState.ApplyOutcome.INVALID, state.applyEncoded(null));
        assertEquals(LoginSettingsSyncState.ApplyOutcome.INVALID, state.applyEncoded(invalidVersion));
        assertEquals(LoginSettingsSyncState.ApplyOutcome.INVALID,
            state.applyEncoded(new byte[LoginSettingsSnapshotCodec.MAX_ENCODED_BYTES + 1]));
        assertEquals(LoginSettingsSyncState.State.EMPTY, state.state());
        assertTrue(state.snapshot().isEmpty());
        assertTrue(applications.isEmpty());

        LoginSettingsSnapshot retry = snapshot("Retry", 3);
        assertEquals(LoginSettingsSyncState.ApplyOutcome.APPLIED,
            state.applyEncoded(LoginSettingsSnapshotCodec.encode(retry)));
        assertEquals(retry, state.snapshot().orElseThrow());
        assertEquals(List.of(retry), applications);
    }

    @Test
    void transientApplicationFailurePublishesNothingAndCanRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<LoginSettingsSnapshot> applied = new AtomicReference<>();
        LoginSettingsSyncState state = new LoginSettingsSyncState(candidate -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient application failure");
            }
            applied.set(candidate);
        });
        LoginSettingsSnapshot snapshot = snapshot("Retry", 3);
        byte[] encoded = LoginSettingsSnapshotCodec.encode(snapshot);

        assertThrows(IllegalStateException.class, () -> state.applyEncoded(encoded));
        assertEquals(LoginSettingsSyncState.State.EMPTY, state.state());
        assertTrue(state.snapshot().isEmpty());
        assertNull(applied.get());

        assertEquals(LoginSettingsSyncState.ApplyOutcome.APPLIED, state.applyEncoded(encoded));
        assertEquals(2, attempts.get());
        assertEquals(snapshot, applied.get());
        assertEquals(snapshot, state.snapshot().orElseThrow());
    }

    @Test
    void applicationCallbackReentryFailsClosedWithoutDoublePublish() {
        AtomicReference<LoginSettingsSyncState> reference = new AtomicReference<>();
        AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
        LoginSettingsSyncState state = new LoginSettingsSyncState(candidate -> {
            try {
                reference.get().apply(snapshot("nested", 2));
            } catch (Throwable failure) {
                nestedFailure.set(failure);
            }
        });
        reference.set(state);

        assertThrows(IllegalStateException.class, () -> state.apply(snapshot("outer", 1)));
        assertTrue(nestedFailure.get() instanceof IllegalStateException);
        assertEquals(LoginSettingsSyncState.State.CLOSED, state.state());
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void closeClearsPublishedStateAndPermanentlyInvalidatesThatSession() {
        LoginSettingsSyncState state = new LoginSettingsSyncState();
        LoginSettingsSnapshot snapshot = snapshot("Session", 1);
        state.apply(snapshot);

        state.close();
        state.disconnect();

        assertEquals(LoginSettingsSyncState.State.CLOSED, state.state());
        assertTrue(state.snapshot().isEmpty());
        assertThrows(IllegalStateException.class, () -> state.apply(snapshot));
        assertThrows(IllegalStateException.class,
            () -> state.applyEncoded(LoginSettingsSnapshotCodec.encode(snapshot)));
    }

    @Test
    void closedSnapshotCannotLeakIntoAReplacementConnectionSession() {
        LoginSettingsSnapshot snapshot = snapshot("Shared payload", 4);
        byte[] encoded = LoginSettingsSnapshotCodec.encode(snapshot);
        LoginSettingsSyncState oldSession = new LoginSettingsSyncState();
        oldSession.applyEncoded(encoded);
        oldSession.close();

        LoginSettingsSyncState newSession = new LoginSettingsSyncState();
        assertTrue(newSession.snapshot().isEmpty());
        assertEquals(LoginSettingsSyncState.ApplyOutcome.APPLIED, newSession.applyEncoded(encoded));
        assertEquals(snapshot, newSession.snapshot().orElseThrow());
        assertTrue(oldSession.snapshot().isEmpty());
    }

    @Test
    void nonOwnerCloseAndDisconnectClearStateAndPreventLaterOwnerApply() throws Exception {
        assertNonOwnerTeardown(LoginSettingsSyncState::close);
        assertNonOwnerTeardown(LoginSettingsSyncState::disconnect);
    }

    private static void assertNonOwnerTeardown(Consumer<LoginSettingsSyncState> teardown)
        throws InterruptedException {
        LoginSettingsSyncState state = new LoginSettingsSyncState();
        LoginSettingsSnapshot published = snapshot("Published", 1);
        state.apply(published);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread lifecycleThread = new Thread(() -> {
            try {
                teardown.accept(state);
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        lifecycleThread.start();
        lifecycleThread.join();

        assertNull(thrown.get());
        assertEquals(LoginSettingsSyncState.State.CLOSED, state.state());
        assertTrue(state.isClosed());
        assertTrue(state.snapshot().isEmpty());
        assertThrows(IllegalStateException.class, () -> state.apply(published));
        assertThrows(IllegalStateException.class,
            () -> state.applyEncoded(LoginSettingsSnapshotCodec.encode(published)));
    }

    @Test
    void allSessionStateAccessBelongsToTheCreatingThread() throws Exception {
        LoginSettingsSyncState state = new LoginSettingsSyncState();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                state.apply(snapshot("Wrong thread", 1));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        other.start();
        other.join();

        assertTrue(thrown.get() instanceof IllegalStateException);
        assertEquals(LoginSettingsSyncState.State.EMPTY, state.state());
        assertTrue(state.snapshot().isEmpty());
    }

    private static LoginSettingsSnapshot snapshot(String packName, int packVersion) {
        return new LoginSettingsSnapshot(
            packName, packVersion, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }
}
