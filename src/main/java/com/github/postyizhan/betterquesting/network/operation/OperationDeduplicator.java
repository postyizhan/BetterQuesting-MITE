package com.github.postyizhan.betterquesting.network.operation;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps successful mutation results for in-process connection sessions.
 *
 * <p>The commit callback runs while the state lock is held. A result is inserted only after the
 * callback returns, so concurrent requests for one operation cannot commit twice. This is an
 * in-memory, single-process guard; it is not persisted or shared across processes.</p>
 */
public final class OperationDeduplicator implements AutoCloseable {
    private final Object lock = new Object();
    private final OperationDeduplicationLimits limits;
    private final Map<String, SessionState> sessions = new HashMap<String, SessionState>();
    private final LinkedHashMap<CacheKey, CompletedOperation> completed =
        new LinkedHashMap<CacheKey, CompletedOperation>();

    private long lastObservedNanos;
    private boolean hasObservedTime;
    private boolean closed;

    public OperationDeduplicator(OperationDeduplicationLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public OperationDeduplicationLimits limits() {
        return limits;
    }

    public Session openSession(String connectionSessionId, PlayerIdentity playerIdentity) {
        validateIdentifier(connectionSessionId, "connection session ID");
        Objects.requireNonNull(playerIdentity, "playerIdentity");
        synchronized (lock) {
            ensureOpen();
            if (sessions.containsKey(connectionSessionId)) {
                throw new IllegalStateException("operation session is already open: " + connectionSessionId);
            }
            SessionState state = new SessionState(connectionSessionId, playerIdentity);
            sessions.put(connectionSessionId, state);
            return new Session(state);
        }
    }

    public int expire(long nowNanos) {
        synchronized (lock) {
            ensureOpen();
            return maintainAt(nowNanos);
        }
    }

    public int cachedOperationCount() {
        synchronized (lock) {
            return completed.size();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            for (SessionState state : sessions.values()) {
                state.closed = true;
            }
            sessions.clear();
            completed.clear();
            closed = true;
        }
    }

    private <T> Result<T> execute(
        SessionState state,
        String operationId,
        String operationType,
        long nowNanos,
        OperationMutation<T> mutation
    ) throws Exception {
        validateIdentifier(operationId, "operation ID");
        validateIdentifier(operationType, "operation type");
        Objects.requireNonNull(mutation, "mutation");

        synchronized (lock) {
            ensureOpen();
            ensureSessionOpen(state);
            maintainAt(nowNanos);

            CacheKey key = new CacheKey(state, operationId);
            CompletedOperation existing = completed.get(key);
            if (existing != null) {
                if (!existing.operationType.equals(operationType)
                    || !existing.playerIdentity.equals(state.playerIdentity)) {
                    return Result.rejected(RejectionReason.OPERATION_ID_OWNERSHIP_CONFLICT);
                }
                @SuppressWarnings("unchecked")
                T value = (T) existing.value;
                return Result.duplicate(value);
            }

            // Do not put anything in completed until the callback has returned successfully.
            T value = mutation.commit();
            ensureOpen();
            ensureSessionOpen(state);
            completed.put(key, new CompletedOperation(
                state.playerIdentity, operationType, value, nowNanos));
            trimToLimit();
            return Result.executed(value);
        }
    }

    private void closeSession(SessionState state) {
        synchronized (lock) {
            if (state.closed) {
                return;
            }
            state.closed = true;
            sessions.remove(state.connectionSessionId, state);
            Iterator<CacheKey> entries = completed.keySet().iterator();
            while (entries.hasNext()) {
                if (entries.next().session == state) {
                    entries.remove();
                }
            }
        }
    }

    private int maintainAt(long nowNanos) {
        if (hasObservedTime && nowNanos < lastObservedNanos) {
            throw new IllegalArgumentException("monotonic time regressed");
        }
        lastObservedNanos = nowNanos;
        hasObservedTime = true;

        int expired = 0;
        Iterator<Map.Entry<CacheKey, CompletedOperation>> entries = completed.entrySet().iterator();
        while (entries.hasNext()) {
            CompletedOperation operation = entries.next().getValue();
            if (hasElapsed(nowNanos, operation.completedAtNanos)) {
                entries.remove();
                expired++;
            }
        }
        return expired;
    }

    private boolean hasElapsed(long nowNanos, long completedAtNanos) {
        long elapsed = nowNanos - completedAtNanos;
        return elapsed < 0L || elapsed >= limits.expiryNanos();
    }

    private void trimToLimit() {
        while (completed.size() > limits.maxEntries()) {
            completed.remove(completed.keySet().iterator().next());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("operation deduplicator is closed");
        }
    }

    private static void ensureSessionOpen(SessionState state) {
        if (state.closed) {
            throw new IllegalStateException("operation session is closed");
        }
    }

    private static void validateIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private final class SessionState {
        private final String connectionSessionId;
        private final PlayerIdentity playerIdentity;
        private boolean closed;

        private SessionState(String connectionSessionId, PlayerIdentity playerIdentity) {
            this.connectionSessionId = connectionSessionId;
            this.playerIdentity = playerIdentity;
        }
    }

    private record CacheKey(SessionState session, String operationId) {
    }

    private static final class CompletedOperation {
        private final PlayerIdentity playerIdentity;
        private final String operationType;
        private final Object value;
        private final long completedAtNanos;

        private CompletedOperation(
            PlayerIdentity playerIdentity,
            String operationType,
            Object value,
            long completedAtNanos
        ) {
            this.playerIdentity = playerIdentity;
            this.operationType = operationType;
            this.value = value;
            this.completedAtNanos = completedAtNanos;
        }
    }

    @FunctionalInterface
    public interface OperationMutation<T> {
        T commit() throws Exception;
    }

    public final class Session implements AutoCloseable {
        private final SessionState state;

        private Session(SessionState state) {
            this.state = state;
        }

        public String connectionSessionId() {
            return state.connectionSessionId;
        }

        public PlayerIdentity playerIdentity() {
            return state.playerIdentity;
        }

        public boolean isClosed() {
            synchronized (lock) {
                return state.closed;
            }
        }

        public <T> Result<T> execute(
            String operationId,
            String operationType,
            long nowNanos,
            OperationMutation<T> mutation
        ) throws Exception {
            return OperationDeduplicator.this.execute(
                state, operationId, operationType, nowNanos, mutation);
        }

        public void disconnect() {
            close();
        }

        @Override
        public void close() {
            OperationDeduplicator.this.closeSession(state);
        }
    }

    public enum Outcome {
        EXECUTED,
        DUPLICATE,
        REJECTED
    }

    public enum RejectionReason {
        NONE,
        OPERATION_ID_OWNERSHIP_CONFLICT
    }

    public record Result<T>(Outcome outcome, RejectionReason rejectionReason, Optional<T> value) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            Objects.requireNonNull(value, "value");
        }

        private static <T> Result<T> executed(T value) {
            return new Result<T>(Outcome.EXECUTED, RejectionReason.NONE, Optional.ofNullable(value));
        }

        private static <T> Result<T> duplicate(T value) {
            return new Result<T>(Outcome.DUPLICATE, RejectionReason.NONE, Optional.ofNullable(value));
        }

        private static <T> Result<T> rejected(RejectionReason reason) {
            return new Result<T>(Outcome.REJECTED, reason, Optional.empty());
        }
    }
}
