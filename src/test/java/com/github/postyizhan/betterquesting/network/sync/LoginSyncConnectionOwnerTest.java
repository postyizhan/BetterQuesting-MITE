package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginSyncConnectionOwnerTest {
    private static final HandshakeLimits LIMITS = new HandshakeLimits(8, 1L, 0L);
    private static final HandshakeCapabilities CAPABILITIES =
        new HandshakeCapabilities(1, 1, 1L, 0L);
    private static final UUID TOKEN =
        UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Test
    void handlerKeysUseReferenceIdentity() {
        Object server = new Object();
        Object firstHandler = new String("equal handler");
        Object secondHandler = new String("equal handler");
        LoginSyncConnectionOwner owner = serverOwner();

        LoginSyncSession first = owner.bind(server, firstHandler);
        LoginSyncSession second = owner.bind(server, secondHandler);

        assertEquals(firstHandler, secondHandler);
        assertNotSame(firstHandler, secondHandler);
        assertNotSame(first, second);
        assertEquals(2, owner.size());
        assertTrue(owner.unbind(firstHandler));
        assertEquals(1, owner.size());
        assertFalse(owner.unbind(firstHandler));
        assertSame(second, owner.bind(server, secondHandler));
    }

    @Test
    void bindCreatesOnCallingThreadAndDuplicateBindIsIdempotent() {
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<Thread> factoryThread = new AtomicReference<>();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> {
                creations.incrementAndGet();
                factoryThread.set(Thread.currentThread());
                return session(role, () -> { });
            });
        Object handler = new Object();

        LoginSyncSession first = owner.bind(handler);
        LoginSyncSession duplicate = owner.bind(handler);

        assertSame(first, duplicate);
        assertSame(Thread.currentThread(), factoryThread.get());
        assertEquals(1, creations.get());
        assertEquals(1, owner.size());
    }

    @Test
    void concurrentDuplicateBindPublishesOneSession() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<LoginSyncSession> first = new AtomicReference<>();
        AtomicReference<LoginSyncSession> second = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Object handler = new Object();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> {
                creations.incrementAndGet();
                factoryEntered.countDown();
                await(releaseFactory);
                return session(role, () -> { });
            });
        Thread firstBinder = threadBinding(owner, handler, first, firstFailure);
        Thread secondBinder = threadBinding(owner, handler, second, secondFailure);

        firstBinder.start();
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
        secondBinder.start();
        releaseFactory.countDown();
        firstBinder.join();
        secondBinder.join();

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertSame(first.get(), second.get());
        assertEquals(1, creations.get());
        assertEquals(1, owner.size());
    }

    @Test
    void clientReconnectClosesOldBeforeCreatingNewAndIgnoresStaleHandlerTeardown() {
        List<String> lifecycle = new ArrayList<>();
        AtomicInteger creation = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> {
                int index = creation.incrementAndGet();
                lifecycle.add("create-" + index);
                return session(role, () -> lifecycle.add("clear-" + index));
            });
        Object oldHandler = new Object();
        Object newHandler = new Object();

        LoginSyncSession oldSession = owner.bind(oldHandler);
        LoginSyncSession newSession = owner.bind(newHandler);

        assertEquals(List.of("create-1", "clear-1", "create-2"), lifecycle);
        assertTrue(oldSession.isClosed());
        assertFalse(newSession.isClosed());
        assertFalse(owner.unbind(oldHandler));
        assertFalse(owner.unbind(oldHandler, oldSession));
        assertFalse(newSession.isClosed());
        assertSame(newSession, owner.bind(newHandler));
    }

    @Test
    void staleSessionTeardownCannotRemoveSameHandlerReplacement() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> session(role, clears::incrementAndGet));
        Object handler = new Object();
        LoginSyncSession first = owner.bind(handler);

        LoginSyncSession replacement = owner.rebind(handler);

        assertNotSame(first, replacement);
        assertEquals(1, clears.get());
        assertFalse(owner.unbind(handler, first));
        assertFalse(replacement.isClosed());
        assertSame(replacement, owner.bind(handler));
        assertTrue(owner.unbind(handler, replacement));
        assertEquals(2, clears.get());
    }

    @Test
    void allClientTerminalCallbacksShareIdempotentUnbind() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, handler) -> session(role, clears::incrementAndGet));
        Object handler = new Object();
        LoginSyncSession session = owner.bind(handler);

        assertTrue(owner.unbind(handler, session));
        assertFalse(owner.unbind(handler, session));
        assertFalse(owner.unbind(handler));
        assertFalse(owner.unbind(handler));
        assertFalse(owner.unbind(handler, session));

        assertEquals(1, clears.get());
        assertTrue(session.isClosed());
        assertEquals(0, owner.size());
    }

    @Test
    void exactServerCloseAllAndLogoutAreOrderIndependent() {
        Object firstServer = new String("equal server");
        Object secondServer = new String("equal server");
        Object firstHandler = new Object();
        Object secondHandler = new Object();
        Object otherHandler = new Object();
        AtomicInteger firstClears = new AtomicInteger();
        AtomicInteger secondClears = new AtomicInteger();
        AtomicInteger otherClears = new AtomicInteger();
        AtomicInteger creation = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, handler) -> {
                int index = creation.incrementAndGet();
                return session(role, index == 1
                    ? firstClears::incrementAndGet
                    : index == 2 ? secondClears::incrementAndGet : otherClears::incrementAndGet);
            });

        LoginSyncSession first = owner.bind(firstServer, firstHandler);
        LoginSyncSession second = owner.bind(firstServer, secondHandler);
        LoginSyncSession other = owner.bind(secondServer, otherHandler);
        assertEquals(firstServer, secondServer);
        assertNotSame(firstServer, secondServer);

        assertTrue(owner.unbind(firstHandler, first));
        assertEquals(1, owner.closeAll(firstServer));
        assertFalse(owner.unbind(secondHandler, second));
        assertEquals(1, firstClears.get());
        assertEquals(1, secondClears.get());
        assertEquals(0, otherClears.get());
        assertFalse(other.isClosed());

        LoginSyncSession replacement = owner.bind(secondServer, firstHandler);
        assertEquals(0, owner.closeAll(firstServer));
        assertFalse(replacement.isClosed());
        assertTrue(owner.unbind(firstHandler, replacement));
        assertEquals(1, otherClears.get());
    }

    @Test
    void closeAllAttemptsEveryRemovedBindingAndSuppressesLaterFailures() {
        Object server = new Object();
        AtomicInteger creations = new AtomicInteger();
        List<AtomicInteger> clears = List.of(
            new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        List<Error> failures = new ArrayList<>();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> {
                int index = creations.getAndIncrement();
                return session(role, () -> {
                    clears.get(index).incrementAndGet();
                    Error failure = new AssertionError("clear-" + index);
                    failures.add(failure);
                    throw failure;
                });
            });
        owner.bind(server, new Object());
        owner.bind(server, new Object());
        owner.bind(server, new Object());

        Error thrown = assertThrows(Error.class, () -> owner.closeAll(server));

        assertEquals(3, failures.size());
        assertSame(failures.get(0), thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(failures.get(1), thrown.getSuppressed()[0]);
        assertSame(failures.get(2), thrown.getSuppressed()[1]);
        assertTrue(clears.stream().allMatch(clear -> clear.get() == 1));
        assertEquals(0, owner.size());
        assertEquals(0, owner.closeAll(server));
        assertTrue(clears.stream().allMatch(clear -> clear.get() == 1));
    }

    @Test
    void staleServerOwnerTeardownCannotRemoveReplacementForSameHandler() {
        Object firstServer = new Object();
        Object secondServer = new Object();
        Object handler = new Object();
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> session(role, clears::incrementAndGet));

        LoginSyncSession first = owner.bind(firstServer, handler);
        LoginSyncSession replacement = owner.rebind(secondServer, handler);

        assertTrue(first.isClosed());
        assertFalse(replacement.isClosed());
        assertFalse(owner.unbind(firstServer, handler, first));
        assertFalse(owner.closeAll(firstServer) > 0);
        assertFalse(replacement.isClosed());
        assertTrue(owner.unbind(secondServer, handler, replacement));
        assertEquals(2, clears.get());
    }

    @Test
    void clientAndServerOwnersRemainIsolated() {
        AtomicInteger clientClears = new AtomicInteger();
        AtomicInteger serverClears = new AtomicInteger();
        Object handler = new Object();
        Object server = new Object();
        LoginSyncConnectionOwner client = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> session(role, clientClears::incrementAndGet));
        LoginSyncConnectionOwner serverOwner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> session(role, serverClears::incrementAndGet));

        LoginSyncSession clientSession = client.bind(handler);
        LoginSyncSession serverSession = serverOwner.bind(server, handler);
        client.close();

        assertTrue(clientSession.isClosed());
        assertFalse(serverSession.isClosed());
        assertEquals(1, clientClears.get());
        assertEquals(0, serverClears.get());
        assertEquals(1, serverOwner.size());
    }

    @Test
    void receiveIsOwnerConfinedWhileCrossThreadTeardownSucceeds() throws Exception {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> session(role, clears::incrementAndGet));
        Object server = new Object();
        Object handler = new Object();
        owner.bind(server, handler);
        LoginSyncFrame hello = LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, CAPABILITIES));

        assertEquals(LoginSyncSession.Outcome.ACCEPTED,
            owner.receive(handler, hello).orElseThrow().outcome());
        assertTrue(owner.receive(new Object(), hello).isEmpty());

        AtomicReference<Throwable> receiveFailure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                owner.receive(handler, hello);
            } catch (Throwable failure) {
                receiveFailure.set(failure);
            }
        });
        wrongThread.start();
        wrongThread.join();

        assertTrue(receiveFailure.get() instanceof IllegalStateException);
        assertTrue(receiveFailure.get().getMessage().contains("non-owner"));

        AtomicReference<Throwable> teardownFailure = new AtomicReference<>();
        Thread teardown = new Thread(() -> {
            try {
                owner.unbind(handler);
            } catch (Throwable failure) {
                teardownFailure.set(failure);
            }
        });
        teardown.start();
        teardown.join();

        assertNull(teardownFailure.get());
        assertEquals(1, clears.get());
        assertTrue(owner.receive(handler, hello).isEmpty());
    }

    @Test
    void receiveEncodedIsOwnerConfinedAndRemovesTerminalSession() throws Exception {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> session(role, clears::incrementAndGet));
        Object server = new Object();
        Object handler = new Object();
        owner.bind(server, handler);
        byte[] hello = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, CAPABILITIES)));

        AtomicReference<Throwable> receiveFailure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                owner.receiveEncoded(handler, hello);
            } catch (Throwable failure) {
                receiveFailure.set(failure);
            }
        });
        wrongThread.start();
        wrongThread.join();

        assertTrue(receiveFailure.get() instanceof IllegalStateException);
        assertTrue(receiveFailure.get().getMessage().contains("non-owner"));
        assertEquals(LoginSyncSession.Outcome.ACCEPTED,
            owner.receiveEncoded(handler, hello).orElseThrow().outcome());

        byte[] conflictingHello = LoginSyncFrameCodec.encode(LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, new HandshakeCapabilities(1, 1, 0L, 0L))));
        assertEquals(LoginSyncSession.Outcome.CONFLICT,
            owner.receiveEncoded(handler, conflictingHello).orElseThrow().outcome());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
        assertTrue(owner.current(handler).isEmpty());
        assertTrue(owner.receiveEncoded(handler, hello).isEmpty());
    }

    @Test
    void concurrentTeardownCancelsRebindAndClosesEveryCreatedSessionOnce() throws Exception {
        CountDownLatch replacementFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseReplacementFactory = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger oldClears = new AtomicInteger();
        AtomicInteger replacementClears = new AtomicInteger();
        AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
        Object handler = new Object();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> {
                if (creations.incrementAndGet() == 1) {
                    return session(role, oldClears::incrementAndGet);
                }
                replacementFactoryEntered.countDown();
                await(releaseReplacementFactory);
                return session(role, replacementClears::incrementAndGet);
            });
        owner.bind(handler);
        Thread rebind = new Thread(() -> {
            try {
                owner.rebind(handler);
            } catch (Throwable failure) {
                rebindFailure.set(failure);
            }
        });

        rebind.start();
        assertTrue(replacementFactoryEntered.await(5, TimeUnit.SECONDS));
        assertTrue(owner.unbind(handler));
        releaseReplacementFactory.countDown();
        rebind.join();

        assertTrue(rebindFailure.get() instanceof IllegalStateException);
        assertEquals(1, oldClears.get());
        assertEquals(1, replacementClears.get());
        assertEquals(0, owner.size());
    }

    @Test
    void cancelledCandidateCleanupFinishesBeforeWaitingBindCanPublish() throws Exception {
        CountDownLatch candidateFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseCandidateFactory = new CountDownLatch(1);
        CountDownLatch candidateCleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCandidateCleanup = new CountDownLatch(1);
        CountDownLatch waitingBindFinished = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger oldClears = new AtomicInteger();
        AtomicInteger candidateClears = new AtomicInteger();
        AtomicReference<LoginSyncSession> waitingResult = new AtomicReference<>();
        AtomicReference<Throwable> rebindFailure = new AtomicReference<>();
        AtomicReference<Throwable> waitingFailure = new AtomicReference<>();
        Object handler = new Object();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> {
                int creation = creations.incrementAndGet();
                if (creation == 1) {
                    return session(role, oldClears::incrementAndGet);
                }
                if (creation == 2) {
                    candidateFactoryEntered.countDown();
                    await(releaseCandidateFactory);
                    return session(role, () -> {
                        candidateClears.incrementAndGet();
                        candidateCleanupEntered.countDown();
                        await(releaseCandidateCleanup);
                    });
                }
                return session(role, () -> { });
            });
        owner.bind(handler);
        Thread rebind = new Thread(() -> {
            try {
                owner.rebind(handler);
            } catch (Throwable failure) {
                rebindFailure.set(failure);
            }
        });
        Thread waitingBind = new Thread(() -> {
            try {
                waitingResult.set(owner.bind(handler));
            } catch (Throwable failure) {
                waitingFailure.set(failure);
            } finally {
                waitingBindFinished.countDown();
            }
        });

        rebind.start();
        assertTrue(candidateFactoryEntered.await(5, TimeUnit.SECONDS));
        assertTrue(owner.unbind(handler));
        waitingBind.start();
        awaitWaiting(waitingBind);
        releaseCandidateFactory.countDown();
        assertTrue(candidateCleanupEntered.await(5, TimeUnit.SECONDS));
        try {
            assertFalse(waitingBindFinished.await(1, TimeUnit.SECONDS));
            assertEquals(0, owner.size());
        } finally {
            releaseCandidateCleanup.countDown();
            rebind.join();
            waitingBind.join();
        }

        assertTrue(rebindFailure.get() instanceof IllegalStateException);
        assertNull(waitingFailure.get());
        assertSame(waitingResult.get(), owner.current(handler).orElseThrow());
        assertEquals(3, creations.get());
        assertEquals(1, oldClears.get());
        assertEquals(1, candidateClears.get());
        assertEquals(1, owner.size());
    }

    @Test
    void factoryFailureLeavesNoReplacementAndOldCleanupRunsOnce() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> {
                if (attempts.incrementAndGet() == 2) {
                    throw new IllegalStateException("factory failed");
                }
                return session(role, clears::incrementAndGet);
            });
        Object handler = new Object();
        LoginSyncSession old = owner.bind(handler);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> owner.rebind(handler));

        assertEquals("factory failed", failure.getMessage());
        assertTrue(old.isClosed());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
        assertTrue(owner.receive(handler, LoginSyncFrame.clientHello(
            new HandshakeHello(TOKEN, CAPABILITIES))).isEmpty());
    }

    @Test
    void invalidFactorySessionIsClosedAndNeverPublished() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> session(LoginSyncSession.Role.SERVER, clears::incrementAndGet));

        assertThrows(IllegalStateException.class, () -> owner.bind(new Object()));

        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
    }

    @Test
    void nullFactorySessionIsRejectedAndDoesNotBlockLaterBind() {
        AtomicInteger attempts = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> attempts.incrementAndGet() == 1
                ? null
                : session(role, () -> { }));
        Object handler = new Object();

        NullPointerException failure = assertThrows(
            NullPointerException.class, () -> owner.bind(handler));

        assertEquals("sessionFactory returned null", failure.getMessage());
        assertEquals(0, owner.size());
        assertSame(owner.bind(handler), owner.current(handler).orElseThrow());
        assertEquals(2, attempts.get());
    }

    @Test
    void closedFactorySessionIsRejectedAndDoesNotBlockLaterBind() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT,
            (role, ignored) -> {
                LoginSyncSession candidate = session(role, clears::incrementAndGet);
                if (attempts.incrementAndGet() == 1) {
                    candidate.close();
                }
                return candidate;
            });
        Object handler = new Object();

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> owner.bind(handler));

        assertEquals("sessionFactory returned a closed session", failure.getMessage());
        assertEquals(1, clears.get());
        assertEquals(0, owner.size());
        assertSame(owner.bind(handler), owner.current(handler).orElseThrow());
        assertEquals(2, attempts.get());
    }

    @Test
    void ownerCloseIsIdempotentAndTerminal() {
        AtomicInteger clears = new AtomicInteger();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> session(role, clears::incrementAndGet));
        owner.bind(new Object(), new Object());
        owner.bind(new Object(), new Object());

        owner.close();
        owner.close();

        assertTrue(owner.isClosed());
        assertEquals(0, owner.size());
        assertEquals(2, clears.get());
        assertThrows(IllegalStateException.class,
            () -> owner.bind(new Object(), new Object()));
    }

    @Test
    void ownerCloseAttemptsEveryRemovedBindingAndSuppressesLaterFailures() {
        AtomicInteger creations = new AtomicInteger();
        List<AtomicInteger> clears = List.of(
            new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        List<Error> failures = new ArrayList<>();
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, ignored) -> {
                int index = creations.getAndIncrement();
                return session(role, () -> {
                    clears.get(index).incrementAndGet();
                    Error failure = new AssertionError("clear-" + index);
                    failures.add(failure);
                    throw failure;
                });
            });
        owner.bind(new Object(), new Object());
        owner.bind(new Object(), new Object());
        owner.bind(new Object(), new Object());

        Error thrown = assertThrows(Error.class, owner::close);

        assertEquals(3, failures.size());
        assertSame(failures.get(0), thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(failures.get(1), thrown.getSuppressed()[0]);
        assertSame(failures.get(2), thrown.getSuppressed()[1]);
        assertTrue(clears.stream().allMatch(clear -> clear.get() == 1));
        assertTrue(owner.isClosed());
        assertEquals(0, owner.size());
        owner.close();
        assertTrue(clears.stream().allMatch(clear -> clear.get() == 1));
    }

    private static LoginSyncConnectionOwner serverOwner() {
        return new LoginSyncConnectionOwner(
            LoginSyncSession.Role.SERVER,
            (role, handler) -> session(role, () -> { }));
    }

    private static LoginSyncSession session(LoginSyncSession.Role role, Runnable clear) {
        return new LoginSyncSession(role, CAPABILITIES, LIMITS, ignored -> { }, clear);
    }

    private static Thread threadBinding(
        LoginSyncConnectionOwner owner,
        Object handler,
        AtomicReference<LoginSyncSession> result,
        AtomicReference<Throwable> failure
    ) {
        return new Thread(() -> {
            try {
                result.set(owner.bind(handler));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", interrupted);
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("thread did not wait for the active transition");
            }
            Thread.yield();
        }
    }
}
