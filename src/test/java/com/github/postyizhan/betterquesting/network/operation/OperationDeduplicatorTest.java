package com.github.postyizhan.betterquesting.network.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperationDeduplicatorTest {
    private static final OperationDeduplicationLimits LIMITS =
        new OperationDeduplicationLimits(2, 10L);
    private static final PlayerIdentity PLAYER_ONE =
        new PlayerIdentity(new UUID(0L, 1L), "player_one");
    private static final PlayerIdentity PLAYER_TWO =
        new PlayerIdentity(new UUID(0L, 2L), "player_two");

    @Test
    void successfulOperationIsBoundToSessionPlayerAndTypeAndIsReturnedAsDuplicate() throws Exception {
        OperationDeduplicator deduplicator = new OperationDeduplicator(LIMITS);
        OperationDeduplicator.Session session = deduplicator.openSession("connection-1", PLAYER_ONE);
        AtomicInteger calls = new AtomicInteger();

        OperationDeduplicator.Result<Integer> first = session.execute(
            "operation-1", "quest/complete", 0L, () -> calls.incrementAndGet());
        OperationDeduplicator.Result<Integer> duplicate = session.execute(
            "operation-1", "quest/complete", 1L, () -> calls.incrementAndGet());

        assertEquals(OperationDeduplicator.Outcome.EXECUTED, first.outcome());
        assertEquals(1, first.value().orElseThrow());
        assertEquals(OperationDeduplicator.Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(1, duplicate.value().orElseThrow());
        assertEquals(1, calls.get());
        assertEquals("connection-1", session.connectionSessionId());
        assertEquals(PLAYER_ONE, session.playerIdentity());

        assertEquals(OperationDeduplicator.Outcome.REJECTED,
            session.execute("operation-1", "quest/claim", 2L, () -> 2).outcome());
        assertEquals(OperationDeduplicator.RejectionReason.OPERATION_ID_OWNERSHIP_CONFLICT,
            session.execute("operation-1", "quest/claim", 3L, () -> 2).rejectionReason());
    }

    @Test
    void failedMutationIsNotCachedAndCanRetry() throws Exception {
        OperationDeduplicator.Session session =
            new OperationDeduplicator(LIMITS).openSession("connection-1", PLAYER_ONE);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> session.execute(
            "operation-1", "quest/complete", 0L, () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("not committed");
            }));
        OperationDeduplicator.Result<Integer> retry = session.execute(
            "operation-1", "quest/complete", 1L, () -> calls.incrementAndGet());

        assertEquals(OperationDeduplicator.Outcome.EXECUTED, retry.outcome());
        assertEquals(2, retry.value().orElseThrow());
        assertEquals(2, calls.get());
    }

    @Test
    void expiryAndEntryLimitAreDeterministic() throws Exception {
        OperationDeduplicator deduplicator = new OperationDeduplicator(LIMITS);
        OperationDeduplicator.Session session = deduplicator.openSession("connection-1", PLAYER_ONE);

        session.execute("first", "edit", 0L, () -> "first");
        session.execute("second", "edit", 1L, () -> "second");
        session.execute("third", "edit", 2L, () -> "third");
        assertEquals(2, deduplicator.cachedOperationCount());
        assertEquals(OperationDeduplicator.Outcome.EXECUTED,
            session.execute("first", "edit", 3L, () -> "replayed").outcome());
        assertEquals(OperationDeduplicator.Outcome.DUPLICATE,
            session.execute("third", "edit", 4L, () -> "replayed").outcome());

        assertEquals(1, deduplicator.expire(12L));
        assertEquals(1, deduplicator.cachedOperationCount());
        assertEquals(1, deduplicator.expire(13L));
        assertEquals(0, deduplicator.cachedOperationCount());
        assertEquals(OperationDeduplicator.Outcome.EXECUTED,
            session.execute("third", "edit", 13L, () -> "after-expiry").outcome());
    }

    @Test
    void closeClearsStateAndPreventsOldSessionReuse() throws Exception {
        OperationDeduplicator deduplicator = new OperationDeduplicator(LIMITS);
        OperationDeduplicator.Session oldSession = deduplicator.openSession("connection-1", PLAYER_ONE);
        oldSession.execute("operation-1", "edit", 0L, () -> "old");
        oldSession.close();

        assertEquals(0, deduplicator.cachedOperationCount());
        assertThrows(IllegalStateException.class,
            () -> oldSession.execute("operation-1", "edit", 1L, () -> "must-not-run"));

        OperationDeduplicator.Session newSession = deduplicator.openSession("connection-1", PLAYER_ONE);
        assertEquals(OperationDeduplicator.Outcome.EXECUTED,
            newSession.execute("operation-1", "edit", 2L, () -> "new").outcome());

        newSession.disconnect();
        assertEquals(0, deduplicator.cachedOperationCount());
    }

    @Test
    void concurrentDuplicateCallsCommitOnlyOnce() throws Exception {
        OperationDeduplicator.Session session =
            new OperationDeduplicator(LIMITS).openSession("connection-1", PLAYER_ONE);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        OperationDeduplicator.Result<?>[] results = new OperationDeduplicator.Result<?>[2];

        Thread first = new Thread(() -> runConcurrent(start, failure, () -> results[0] = session.execute(
            "operation-1", "edit", 0L, () -> {
                calls.incrementAndGet();
                return "committed";
            })));
        Thread second = new Thread(() -> runConcurrent(start, failure, () -> results[1] = session.execute(
            "operation-1", "edit", 0L, () -> {
                calls.incrementAndGet();
                return "committed";
            })));
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertFalse(failure.get() != null, () -> String.valueOf(failure.get()));
        assertEquals(1, calls.get());
        assertTrue((results[0].outcome() == OperationDeduplicator.Outcome.EXECUTED
            && results[1].outcome() == OperationDeduplicator.Outcome.DUPLICATE)
            || (results[1].outcome() == OperationDeduplicator.Outcome.EXECUTED
            && results[0].outcome() == OperationDeduplicator.Outcome.DUPLICATE));
    }

    @Test
    void rejectsBlankValuesAndNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new OperationDeduplicationLimits(0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new OperationDeduplicationLimits(-1, 1L));
        assertThrows(IllegalArgumentException.class, () -> new OperationDeduplicationLimits(1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new OperationDeduplicationLimits(1, -1L));

        OperationDeduplicator.Session session =
            new OperationDeduplicator(LIMITS).openSession("connection-1", PLAYER_ONE);
        assertThrows(IllegalArgumentException.class,
            () -> session.execute(" ", "edit", 0L, () -> "value"));
        assertThrows(IllegalArgumentException.class,
            () -> session.execute("operation-1", " ", 0L, () -> "value"));
        assertThrows(NullPointerException.class,
            () -> session.execute("operation-1", "edit", 0L, null));
        assertThrows(IllegalArgumentException.class,
            () -> new OperationDeduplicator(LIMITS).openSession("", PLAYER_ONE));
        assertThrows(NullPointerException.class,
            () -> new OperationDeduplicator(LIMITS).openSession(null, PLAYER_ONE));
        assertThrows(NullPointerException.class,
            () -> new OperationDeduplicator(LIMITS).openSession("connection-1", null));

        OperationDeduplicator duplicateSession = new OperationDeduplicator(LIMITS);
        duplicateSession.openSession("connection-1", PLAYER_ONE);
        assertThrows(IllegalStateException.class,
            () -> duplicateSession.openSession("connection-1", PLAYER_TWO));
        assertThrows(IllegalArgumentException.class, () -> {
            duplicateSession.expire(1L);
            duplicateSession.expire(0L);
        });
    }

    private static void runConcurrent(
        CountDownLatch start,
        AtomicReference<Throwable> failure,
        ThrowingRunnable action
    ) {
        try {
            start.await();
            action.run();
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
