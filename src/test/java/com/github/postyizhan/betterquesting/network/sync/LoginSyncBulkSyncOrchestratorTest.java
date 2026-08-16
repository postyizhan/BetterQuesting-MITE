package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.fragment.FragmentAssemblyLimits;
import com.github.postyizhan.betterquesting.network.fragment.QuestingFragment;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import moddedmite.rustedironcore.network.Packet;
import org.junit.jupiter.api.Test;

class LoginSyncBulkSyncOrchestratorTest {
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final HandshakeCapabilities CAPABILITIES =
        new HandshakeCapabilities(1, 1, 0L, 0L);
    private static final HandshakeLimits HANDSHAKE_LIMITS = new HandshakeLimits(63, 0L, 0L);
    private static final FragmentAssemblyLimits FRAGMENT_LIMITS = new FragmentAssemblyLimits(
        3, 32, 16, 2, 512L, 16, 10L);

    @Test
    void handshakeMustCompleteBeforePublishOrAcceptAndCompletedPayloadPublishesAtomically() {
        List<byte[]> published = new ArrayList<>();
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS, ignored -> { });
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS, payload -> published.add(payload.clone()));

        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.NOT_READY,
            server.publish(new byte[] {1}).outcome());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.NOT_READY,
            client.accept(new QuestingFragment(1L, 1, 0, 1, new byte[] {1}), 0L).outcome());

        LoginSyncFrame clientHello = clientSession.start(TOKEN);
        LoginSyncFrame serverHello = serverSession.receive(clientHello).response().orElseThrow();
        assertEquals(LoginSyncSession.Outcome.ACCEPTED, clientSession.receive(serverHello).outcome());

        byte[] payload = {1, 2, 3, 4, 5, 6, 7};
        LoginSyncBulkSyncOrchestrator.Result fragmented = server.publish(payload);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.FRAGMENTS_CREATED, fragmented.outcome());
        List<QuestingFragment> fragments = fragmented.fragments();
        assertEquals(3, fragments.size());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.ACCEPTED,
            client.accept(fragments.get(2), 1L).outcome());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.ACCEPTED,
            client.accept(fragments.get(0), 2L).outcome());
        LoginSyncBulkSyncOrchestrator.Result completed = client.accept(fragments.get(1), 3L);

        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.PUBLISHED, completed.outcome());
        assertEquals(1, published.size());
        assertArrayEquals(payload, published.get(0));
    }

    @Test
    void malformedAndFailedTransfersNeverPublishPartialStateAndApplicationCanRetry() {
        AtomicInteger applications = new AtomicInteger();
        List<byte[]> published = new ArrayList<>();
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS, ignored -> { });
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS, payload -> {
                if (applications.getAndIncrement() < 2) {
                    throw new IllegalStateException("synthetic sink failure");
                }
                published.add(payload.clone());
            });
        LoginSyncFrame hello = clientSession.start(TOKEN);
        LoginSyncFrame serverHello = serverSession.receive(hello).response().orElseThrow();
        clientSession.receive(serverHello);

        byte[] attempted = {9, 8, 7, 6};
        LoginSyncBulkSyncOrchestrator.Result fragmented = server.publish(41L, attempted);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.FRAGMENTS_CREATED, fragmented.outcome());
        List<QuestingFragment> failed = fragmented.fragments();
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.REJECTED,
            client.acceptEncoded(new byte[] {1, 2, 3}, 4L).outcome());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.ACCEPTED,
            client.accept(failed.get(0), 5L).outcome());
        LoginSyncBulkSyncOrchestrator.Result applicationFailed =
            client.accept(failed.get(1), 6L);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.APPLICATION_FAILED,
            applicationFailed.outcome());
        byte[] recoverable = applicationFailed.completedPayload().orElseThrow();
        assertArrayEquals(attempted, recoverable);
        recoverable[0] = 0;
        assertArrayEquals(attempted, applicationFailed.payload().orElseThrow());
        assertTrue(published.isEmpty());

        LoginSyncBulkSyncOrchestrator.Result retryFailed =
            client.retryApplication(applicationFailed);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.APPLICATION_FAILED,
            retryFailed.outcome());
        assertArrayEquals(attempted, retryFailed.completedPayload().orElseThrow());
        assertThrows(IllegalArgumentException.class,
            () -> client.retryApplication(applicationFailed));

        LoginSyncBulkSyncOrchestrator.Result retryResult =
            client.retryApplication(retryFailed);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.PUBLISHED, retryResult.outcome());
        assertEquals(1, published.size());
        assertArrayEquals(attempted, published.get(0));
        assertThrows(IllegalArgumentException.class,
            () -> client.retryApplication(retryFailed));
    }

    @Test
    void duplicateAndDisconnectDoNotAllowStaleFragmentsToCompleteAfterRebind() {
        AtomicReference<byte[]> publication = new AtomicReference<>();
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS, ignored -> { });
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS, payload -> publication.set(payload.clone()));
        LoginSyncFrame hello = clientSession.start(TOKEN);
        LoginSyncFrame serverHello = serverSession.receive(hello).response().orElseThrow();
        clientSession.receive(serverHello);

        List<QuestingFragment> fragments = server.publish(new byte[] {1, 2, 3, 4}).fragments();
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.ACCEPTED,
            client.accept(fragments.get(0), 0L).outcome());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.DUPLICATE,
            client.accept(fragments.get(0), 1L).outcome());
        client.close();
        assertFalse(client.hasActiveAssemblies());
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.CLOSED,
            client.accept(fragments.get(1), 2L).outcome());
        assertEquals(null, publication.get());
    }

    @Test
    void connectionOwnerCloseHookClearsComposedOrchestratorOnRebind() {
        LoginSyncConnectionOwner owner = new LoginSyncConnectionOwner(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        Object handler = new Object();
        LoginSyncSession session = owner.bind(handler);
        LoginSyncBulkSyncOrchestrator orchestrator = new LoginSyncBulkSyncOrchestrator(
            session, FRAGMENT_LIMITS);
        AtomicInteger cleanup = new AtomicInteger();

        session.addCloseHook(() -> {
            cleanup.incrementAndGet();
            orchestrator.close();
        });
        assertTrue(owner.unbind(handler));
        assertEquals(1, cleanup.get());
        assertTrue(orchestrator.isClosed());
        assertEquals(0, orchestrator.activeTransferCount());
    }

    @Test
    void rejectsWrongRolesAndBoundedOutboundAndEncodedInputs() {
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS);
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS);
        completeHandshake(server, client);

        assertRejected(client.publish(new byte[] {1}),
            LoginSyncBulkSyncOrchestrator.RejectionReason.WRONG_ROLE);
        assertRejected(server.accept(
            new QuestingFragment(50L, 1, 0, 1, new byte[] {1}), 0L),
            LoginSyncBulkSyncOrchestrator.RejectionReason.WRONG_ROLE);
        assertRejected(server.publish((byte[]) null),
            LoginSyncBulkSyncOrchestrator.RejectionReason.INVALID_PAYLOAD);
        assertRejected(server.publish(new byte[0]),
            LoginSyncBulkSyncOrchestrator.RejectionReason.INVALID_PAYLOAD);
        assertRejected(server.publish(new byte[FRAGMENT_LIMITS.maxTransferBytes() + 1]),
            LoginSyncBulkSyncOrchestrator.RejectionReason.OVERSIZED);
        assertRejected(client.acceptEncoded(
            new byte[client.fragmentCodec().maxEncodedBytes() + 1], 0L),
            LoginSyncBulkSyncOrchestrator.RejectionReason.OVERSIZED);
    }

    @Test
    void acceptRejectsInvalidTimeAndIdleExpiryTreatsItAsNeutral() {
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS);
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS);
        completeHandshake(server, client);

        QuestingFragment negative = new QuestingFragment(
            59L, 1, 0, 1, new byte[] {1});
        assertRejected(client.accept(negative, -1L),
            LoginSyncBulkSyncOrchestrator.RejectionReason.MALFORMED);
        assertEquals(0, client.activeTransferCount());

        QuestingFragment first = new QuestingFragment(
            60L, 4, 0, 2, new byte[] {1, 2, 3});
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.ACCEPTED,
            client.accept(first, 5L).outcome());
        QuestingFragment second = new QuestingFragment(
            60L, 4, 1, 2, new byte[] {4});
        assertRejected(client.accept(second, 4L),
            LoginSyncBulkSyncOrchestrator.RejectionReason.MALFORMED);
        assertEquals(0, client.expireIdle(-1L));
        assertEquals(0, client.expireIdle(4L));
        assertEquals(0, client.expireIdle(14L));
        assertEquals(1, client.activeTransferCount());
        assertEquals(1, client.expireIdle(15L));
        assertFalse(client.hasActiveAssemblies());
    }

    @Test
    void composesHandshakeSettingsPacketsAndFragmentWireHelpersWithoutRegistration() {
        List<byte[]> published = new ArrayList<>();
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS);
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession, FRAGMENT_LIMITS, payload -> published.add(payload.clone()));

        assertEquals(clientSession, client.session());
        assertEquals(FRAGMENT_LIMITS, client.limits());
        Packet helloPacket = client.startClientHelloPacket(TOKEN);
        LoginSyncFrame clientHello = LoginSyncTransportPackets.extract(helloPacket).orElseThrow();
        assertArrayEquals(LoginSyncFrameCodec.encode(clientHello), client.encodeFrame(clientHello));
        LoginSyncFrame serverHello = server.receivePacket(helloPacket).response().orElseThrow();
        assertEquals(LoginSyncSession.Outcome.ACCEPTED,
            client.receiveFrameEncoded(server.encodeFrame(serverHello)).outcome());

        LoginSettingsSnapshot settings = snapshot("composed");
        assertEquals(LoginSyncSession.Outcome.PUBLISHED,
            client.receivePacket(server.sendSettingsPacket(settings)).outcome());
        assertEquals(settings, clientSession.publishedSnapshot().orElseThrow());

        byte[] payload = {4, 3, 2, 1};
        LoginSyncBulkSyncOrchestrator.Result fragmented = server.publish(70L, payload);
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.FRAGMENTS_CREATED, fragmented.outcome());
        List<byte[]> encodedFragments = fragmented.encodedFragments(server.fragmentCodec());
        LoginSyncBulkSyncOrchestrator.Result received = null;
        for (int index = 0; index < encodedFragments.size(); index++) {
            received = client.acceptEncoded(encodedFragments.get(index), index);
        }
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.PUBLISHED, received.outcome());
        assertArrayEquals(payload, received.completedPayload().orElseThrow());
        assertArrayEquals(payload, published.get(0));
    }

    @Test
    void applicationCallbackCanWaitForConcurrentTeardown() {
        AtomicReference<LoginSyncSession> reference = new AtomicReference<>();
        CountDownLatch closeFinished = new CountDownLatch(1);
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT,
            CAPABILITIES,
            HANDSHAKE_LIMITS,
            ignored -> {
                Thread teardown = daemonThread(() -> {
                    reference.get().close();
                    closeFinished.countDown();
                });
                teardown.start();
                await(closeFinished);
            });
        reference.set(clientSession);
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncFrame clientHello = clientSession.start(TOKEN);
        clientSession.receive(serverSession.receive(clientHello).response().orElseThrow());

        LoginSyncSession.ReceiveResult result =
            clientSession.receive(serverSession.sendSettings(snapshot("concurrent-close")));

        assertEquals(LoginSyncSession.Outcome.REJECTED, result.outcome());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            result.rejectionReason().orElseThrow());
        assertTrue(clientSession.publishedSnapshot().isEmpty());
    }

    @Test
    void bulkPublicationCallbackCanWaitForConcurrentSessionTeardown() {
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Thread> teardown = new AtomicReference<>();
        LoginSyncSession clientSession = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
            clientSession,
            FRAGMENT_LIMITS,
            ignored -> {
                Thread closeThread = daemonThread(() -> {
                    clientSession.close();
                    closeFinished.countDown();
                });
                teardown.set(closeThread);
                closeThread.start();
                try {
                    if (!closeFinished.await(1L, TimeUnit.SECONDS)) {
                        throw new AssertionError("session teardown waited on the publication callback");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for session teardown", interrupted);
                }
            });
        LoginSyncSession serverSession = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
        LoginSyncBulkSyncOrchestrator server = new LoginSyncBulkSyncOrchestrator(
            serverSession, FRAGMENT_LIMITS);
        completeHandshake(server, client);

        QuestingFragment fragment = server.publish(79L, new byte[] {1}).fragments().get(0);

        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.CLOSED,
            client.accept(fragment, 0L).outcome());
        assertFalse(teardown.get().isAlive());
        assertTrue(client.isClosed());
    }

    @Test
    void clearCallbackDoesNotDeadlockWithOrchestratorSessionQuery() throws InterruptedException {
        CountDownLatch ownerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = daemonThread(() -> {
            try {
                AtomicReference<LoginSyncBulkSyncOrchestrator> orchestrator =
                    new AtomicReference<>();
                AtomicReference<Thread> teardown = new AtomicReference<>();
                CountDownLatch clearEntered = new CountDownLatch(1);
                LoginSyncSession clientSession = new LoginSyncSession(
                    LoginSyncSession.Role.CLIENT,
                    CAPABILITIES,
                    HANDSHAKE_LIMITS,
                    ignored -> { },
                    () -> {
                        clearEntered.countDown();
                        orchestrator.get().close();
                    });
                LoginSyncBulkSyncOrchestrator client = new LoginSyncBulkSyncOrchestrator(
                    clientSession,
                    FRAGMENT_LIMITS,
                    ignored -> {
                        Thread closeThread = daemonThread(clientSession::close);
                        teardown.set(closeThread);
                        closeThread.start();
                        await(clearEntered);
                        clientSession.state();
                    });
                orchestrator.set(client);
                LoginSyncSession serverSession = new LoginSyncSession(
                    LoginSyncSession.Role.SERVER, CAPABILITIES, HANDSHAKE_LIMITS);
                LoginSyncFrame clientHello = clientSession.start(TOKEN);
                clientSession.receive(serverSession.receive(clientHello).response().orElseThrow());

                assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.CLOSED,
                    client.accept(
                        new QuestingFragment(80L, 1, 0, 1, new byte[] {1}), 0L).outcome());
                teardown.get().join(TimeUnit.SECONDS.toMillis(5L));
                assertFalse(teardown.get().isAlive());
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            } finally {
                ownerFinished.countDown();
            }
        });

        owner.start();

        assertTrue(ownerFinished.await(5L, TimeUnit.SECONDS),
            "session/orchestrator lock cycle did not complete");
        if (ownerFailure.get() != null) {
            throw new AssertionError("owner thread failed", ownerFailure.get());
        }
    }

    private static void completeHandshake(
        LoginSyncBulkSyncOrchestrator server,
        LoginSyncBulkSyncOrchestrator client
    ) {
        LoginSyncFrame clientHello = client.startClientHello(TOKEN);
        LoginSyncFrame serverHello = server.receiveFrame(clientHello).response().orElseThrow();
        assertEquals(LoginSyncSession.Outcome.ACCEPTED,
            client.receiveFrame(serverHello).outcome());
    }

    private static void assertRejected(
        LoginSyncBulkSyncOrchestrator.Result result,
        LoginSyncBulkSyncOrchestrator.RejectionReason reason
    ) {
        assertEquals(LoginSyncBulkSyncOrchestrator.Outcome.REJECTED, result.outcome());
        assertEquals(reason, result.rejectionReason().orElseThrow());
    }

    private static LoginSettingsSnapshot snapshot(String name) {
        return new LoginSettingsSnapshot(
            name, 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }

    private static Thread daemonThread(Runnable operation) {
        Thread thread = new Thread(operation);
        thread.setDaemon(true);
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent lifecycle operation");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for lifecycle operation", interrupted);
        }
    }
}

final class LoginSyncFocusedRunner {
    private LoginSyncFocusedRunner() {
    }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;
        for (Class<?> testClass : List.of(
            LoginSyncSessionTest.class,
            LoginSyncBulkSyncOrchestratorTest.class
        )) {
            var constructor = testClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            for (Method method : testClass.getDeclaredMethods()) {
                if (method.getAnnotation(Test.class) == null) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    method.invoke(constructor.newInstance());
                    passed++;
                    System.out.println("PASS " + testClass.getSimpleName() + '#' + method.getName());
                } catch (InvocationTargetException failure) {
                    failed++;
                    System.out.println("FAIL " + testClass.getSimpleName() + '#' + method.getName());
                    failure.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println("Focused tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " focused tests failed");
        }
    }
}
