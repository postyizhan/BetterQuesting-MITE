package com.github.postyizhan.betterquesting.client.state;

import com.github.postyizhan.betterquesting.network.sync.LoginChapterSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Client-owned read-only chapter state scoped to the newest login connection lease. */
public final class ClientChapterState {
    public static final ClientChapterState INSTANCE = new ClientChapterState();

    private final Object lifecycleLock = new Object();
    private ConnectionLease newestLease;
    private ConnectionLease publicationOwner;
    private LoginChapterSnapshot snapshot;

    public Optional<LoginChapterSnapshot> current() {
        synchronized (lifecycleLock) {
            return Optional.ofNullable(snapshot);
        }
    }

    public ConnectionLease openConnectionLease() {
        synchronized (lifecycleLock) {
            ConnectionLease lease = new ConnectionLease();
            newestLease = lease;
            publicationOwner = null;
            snapshot = null;
            return lease;
        }
    }

    public final class ConnectionLease implements AutoCloseable {
        private boolean closed;

        private ConnectionLease() {
        }

        public void publish(LoginChapterSnapshot candidate) {
            Objects.requireNonNull(candidate, "snapshot");
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IllegalStateException("client chapter connection lease is closed");
                }
                if (newestLease != this) {
                    throw new IllegalStateException("client chapter connection lease is stale");
                }
                if (publicationOwner == this) {
                    if (!snapshot.equals(candidate)) {
                        throw new IllegalStateException(
                            "client chapter connection lease has conflicting chapter data");
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
                if (publicationOwner == this) {
                    publicationOwner = null;
                    snapshot = null;
                }
            }
        }
    }
}
