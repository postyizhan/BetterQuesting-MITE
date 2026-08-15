package com.github.postyizhan.betterquesting.network.sync;

import java.util.Objects;
import java.util.Optional;

/** Owner-confined session state with cross-thread lifecycle teardown. */
public final class LoginSettingsSyncState implements AutoCloseable {
    @FunctionalInterface
    public interface SnapshotApplication {
        /** Atomically publishes the complete immutable snapshot or throws without publishing it. */
        void apply(LoginSettingsSnapshot snapshot);
    }

    public enum State {
        EMPTY,
        PUBLISHED,
        CLOSED
    }

    public enum ApplyOutcome {
        APPLIED,
        INVALID,
        DUPLICATE,
        CONFLICT
    }

    private final Thread owner;
    private final Object lifecycleLock = new Object();

    private State state = State.EMPTY;
    private LoginSettingsSnapshot snapshot;
    private SnapshotApplication application;

    public LoginSettingsSyncState() {
        this(ignored -> { });
    }

    public LoginSettingsSyncState(SnapshotApplication application) {
        this.owner = Thread.currentThread();
        this.application = Objects.requireNonNull(application, "application");
    }

    public ApplyOutcome applyEncoded(byte[] encoded) {
        checkOwner();
        synchronized (lifecycleLock) {
            ensureOpen();
            Optional<LoginSettingsSnapshot> decoded = LoginSettingsSnapshotCodec.decode(encoded);
            return decoded.isEmpty() ? ApplyOutcome.INVALID : applyValidated(decoded.orElseThrow());
        }
    }

    public ApplyOutcome apply(LoginSettingsSnapshot candidate) {
        checkOwner();
        synchronized (lifecycleLock) {
            ensureOpen();
            return applyValidated(Objects.requireNonNull(candidate, "candidate"));
        }
    }

    public State state() {
        checkOwner();
        synchronized (lifecycleLock) {
            return state;
        }
    }

    public Optional<LoginSettingsSnapshot> snapshot() {
        checkOwner();
        synchronized (lifecycleLock) {
            return Optional.ofNullable(snapshot);
        }
    }

    public boolean isClosed() {
        checkOwner();
        synchronized (lifecycleLock) {
            return state == State.CLOSED;
        }
    }

    public void disconnect() {
        close();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            snapshot = null;
            application = null;
            state = State.CLOSED;
        }
    }

    private ApplyOutcome applyValidated(LoginSettingsSnapshot candidate) {
        candidate.validateForWire();
        if (snapshot == null) {
            application.apply(candidate);
            ensureOpen();
            snapshot = candidate;
            state = State.PUBLISHED;
            return ApplyOutcome.APPLIED;
        }
        return snapshot.equals(candidate) ? ApplyOutcome.DUPLICATE : ApplyOutcome.CONFLICT;
    }

    private void ensureOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("login settings sync session is closed");
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("login settings sync session accessed from a non-owner thread");
        }
    }
}
