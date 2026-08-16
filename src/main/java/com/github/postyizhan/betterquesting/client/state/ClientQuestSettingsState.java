package com.github.postyizhan.betterquesting.client.state;

import com.github.postyizhan.betterquesting.network.sync.LoginSettingsSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Client-owned read state for the settings published by the active login connection. */
public final class ClientQuestSettingsState {
    public static final ClientQuestSettingsState INSTANCE = new ClientQuestSettingsState();

    private final Object lifecycleLock = new Object();
    private ConnectionLease newestLease;
    private ConnectionLease publicationOwner;
    private LoginSettingsSnapshot snapshot;

    public Optional<LoginSettingsSnapshot> current() {
        synchronized (lifecycleLock) {
            return Optional.ofNullable(snapshot);
        }
    }

    public ConnectionLease openConnectionLease() {
        synchronized (lifecycleLock) {
            ConnectionLease lease = new ConnectionLease();
            newestLease = lease;
            return lease;
        }
    }

    public final class ConnectionLease implements AutoCloseable {
        private boolean closed;

        private ConnectionLease() {
        }

        public void publish(LoginSettingsSnapshot candidate) {
            Objects.requireNonNull(candidate, "snapshot");
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IllegalStateException("client settings connection lease is closed");
                }
                if (newestLease != this) {
                    throw new IllegalStateException("client settings connection lease is stale");
                }
                if (publicationOwner == this) {
                    if (!snapshot.equals(candidate)) {
                        throw new IllegalStateException(
                            "client settings connection lease has conflicting settings");
                    }
                    return;
                }
                snapshot = candidate;
                publicationOwner = this;
            }
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (newestLease == this) {
                    newestLease = null;
                }
                // Publication ownership, not callback order, decides whether teardown may clear.
                if (publicationOwner == this) {
                    publicationOwner = null;
                    snapshot = null;
                }
            }
        }
    }
}
