package com.github.postyizhan.betterquesting.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHelloCodec;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LoginSyncSessionTest {
    private static final HandshakeLimits LIMITS = new HandshakeLimits(8, 1L, 0L);
    private static final HandshakeCapabilities CAPABILITIES =
        new HandshakeCapabilities(1, 1, 1L, 0L);
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Test
    void clientHelloEstablishesTokenServerEchoesItAndSettingsWaitForBothReady() {
        List<LoginSettingsSnapshot> published = new ArrayList<>();
        AtomicInteger cleared = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS, published::add, cleared::incrementAndGet);
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSettingsSnapshot snapshot = snapshot("one");

        LoginSyncFrame clientHello = client.start(TOKEN);
        assertEquals(LoginSyncSession.Outcome.REJECTED,
            client.receive(LoginSyncFrame.settings(clientHello.connectionToken(), snapshot)).outcome());
        LoginSyncSession.ReceiveResult serverResult = server.receive(clientHello);
        LoginSyncFrame serverHello = serverResult.response().orElseThrow();
        assertEquals(clientHello.connectionToken(), serverHello.connectionToken());
        assertEquals(LoginSyncSession.State.READY, server.state());
        assertEquals(LoginSyncSession.Outcome.ACCEPTED, client.receive(serverHello).outcome());
        assertEquals(LoginSyncSession.State.READY, client.state());

        LoginSyncFrame settings = server.sendSettings(snapshot);
        assertEquals(LoginSyncSession.Outcome.PUBLISHED, client.receive(settings).outcome());
        assertEquals(snapshot, client.publishedSnapshot().orElseThrow());
        assertEquals(1, published.size());
        assertEquals(0, cleared.get());
    }

    @Test
    void malformedOversizedWrongDirectionOutOfOrderAndWrongTokenCannotAdvanceOrPublish() {
        List<LoginSettingsSnapshot> published = new ArrayList<>();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS, published::add, () -> { });
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSettingsSnapshot snapshot = snapshot("one");
        LoginSyncFrame hello = client.start(TOKEN);

        assertEquals(LoginSyncSession.RejectionReason.MALFORMED,
            client.receive(new byte[] {1, 2, 3}).rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.RejectionReason.OVERSIZED,
            client.receive(new byte[LoginSyncFrameCodec.MAX_ENCODED_BYTES + 1])
                .rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.RejectionReason.OUT_OF_ORDER,
            client.receive(LoginSyncFrame.settings(hello.connectionToken(), snapshot)).rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.RejectionReason.WRONG_DIRECTION,
            client.receive(LoginSyncFrame.clientHello(hello.hello().orElseThrow())).rejectionReason().orElseThrow());

        LoginSyncFrame serverHello = server.receive(hello).response().orElseThrow();
        UUID otherToken = UUID.randomUUID();
        LoginSyncFrame wrongToken = LoginSyncFrame.serverHello(
            new com.github.postyizhan.betterquesting.network.handshake.HandshakeHello(
                otherToken, CAPABILITIES));
        assertEquals(LoginSyncSession.RejectionReason.WRONG_TOKEN,
            client.receive(wrongToken).rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.HELLO_SENT, client.state());
        assertTrue(published.isEmpty());

        assertEquals(LoginSyncSession.Outcome.ACCEPTED, client.receive(serverHello).outcome());
        assertEquals(LoginSyncSession.RejectionReason.WRONG_DIRECTION,
            server.receive(serverHello).rejectionReason().orElseThrow());

        LoginSyncFrame foreignHello = LoginSyncFrame.clientHello(
            new com.github.postyizhan.betterquesting.network.handshake.HandshakeHello(
                UUID.randomUUID(), CAPABILITIES));
        assertEquals(LoginSyncSession.RejectionReason.WRONG_TOKEN,
            server.receive(foreignHello).rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.READY, server.state());
    }

    @Test
    void serverHelloPayloadTokenMustMatchSessionWithoutAdvancingHandshake() {
        List<LoginSettingsSnapshot> published = new ArrayList<>();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS, published::add, () -> { });
        LoginSyncFrame clientHello = client.start(TOKEN);
        UUID token = clientHello.connectionToken();
        UUID foreignToken = new UUID(token.getMostSignificantBits() ^ 1L, token.getLeastSignificantBits());
        LoginSyncFrame forged = new LoginSyncFrame(
            LoginSyncFrame.Direction.SERVER_TO_CLIENT,
            LoginSyncFrame.Type.SERVER_HELLO,
            token,
            HandshakeHelloCodec.encode(new HandshakeHello(foreignToken, CAPABILITIES)));

        LoginSyncSession.ReceiveResult rejected = client.receive(forged);

        assertEquals(LoginSyncSession.Outcome.REJECTED, rejected.outcome());
        assertEquals(LoginSyncSession.RejectionReason.WRONG_TOKEN,
            rejected.rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.HELLO_SENT, client.state());
        assertTrue(client.publishedSnapshot().isEmpty());
        assertTrue(published.isEmpty());
        assertEquals(LoginSyncSession.Outcome.ACCEPTED,
            client.receive(LoginSyncFrame.serverHello(new HandshakeHello(token, CAPABILITIES))).outcome());
        assertEquals(LoginSyncSession.State.READY, client.state());
        assertEquals(LoginSyncSession.RejectionReason.WRONG_TOKEN,
            client.receive(forged).rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.READY, client.state());
        assertTrue(client.publishedSnapshot().isEmpty());
    }

    @Test
    void handshakeRejectionClosesClientAndServerAndClearsExactlyOnce() {
        AtomicInteger serverCleared = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS, ignored -> { }, () -> { });
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER,
            new HandshakeCapabilities(2, 1, 1L, 0L),
            LIMITS,
            ignored -> { },
            serverCleared::incrementAndGet);
        LoginSyncFrame clientHello = client.start(TOKEN);

        LoginSyncSession.ReceiveResult serverRejected = server.receive(clientHello);

        assertEquals(LoginSyncSession.Outcome.REJECTED, serverRejected.outcome());
        assertEquals(LoginSyncSession.RejectionReason.HANDSHAKE_REJECTED,
            serverRejected.rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.CLOSED, server.state());
        assertEquals(HandshakeSession.State.CLOSED, server.handshakeState());
        assertTrue(server.connectionToken().isEmpty());
        assertTrue(server.localHello().isEmpty());
        assertEquals(1, serverCleared.get());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            server.receive(clientHello).rejectionReason().orElseThrow());
        server.close();
        assertEquals(1, serverCleared.get());

        AtomicInteger clientCleared = new AtomicInteger();
        LoginSyncSession rejectedClient = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            ignored -> { }, clientCleared::incrementAndGet);
        rejectedClient.start(TOKEN);
        LoginSyncFrame incompatibleServerHello = LoginSyncFrame.serverHello(
            new HandshakeHello(TOKEN, new HandshakeCapabilities(2, 1, 1L, 0L)));

        LoginSyncSession.ReceiveResult clientRejected = rejectedClient.receive(incompatibleServerHello);

        assertEquals(LoginSyncSession.Outcome.REJECTED, clientRejected.outcome());
        assertEquals(LoginSyncSession.RejectionReason.HANDSHAKE_REJECTED,
            clientRejected.rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.CLOSED, rejectedClient.state());
        assertEquals(HandshakeSession.State.CLOSED, rejectedClient.handshakeState());
        assertTrue(rejectedClient.connectionToken().isEmpty());
        assertTrue(rejectedClient.localHello().isEmpty());
        assertEquals(1, clientCleared.get());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            rejectedClient.receive(incompatibleServerHello).rejectionReason().orElseThrow());
        rejectedClient.disconnect();
        assertEquals(1, clientCleared.get());
    }

    @Test
    void duplicateHellosAndSettingsAreIdempotentAndRecoverServerHelloLoss() {
        AtomicInteger applications = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            ignored -> applications.incrementAndGet(), () -> { });
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame clientHello = client.start(TOKEN);
        LoginSyncFrame serverHello = server.receive(clientHello).response().orElseThrow();

        LoginSyncSession.ReceiveResult duplicateClientHello = server.receive(clientHello);
        assertEquals(LoginSyncSession.Outcome.DUPLICATE, duplicateClientHello.outcome());
        assertEquals(serverHello, duplicateClientHello.response().orElseThrow());
        assertEquals(LoginSyncSession.Outcome.ACCEPTED, client.receive(serverHello).outcome());
        assertEquals(LoginSyncSession.Outcome.DUPLICATE, client.receive(serverHello).outcome());

        LoginSettingsSnapshot snapshot = snapshot("duplicate");
        LoginSyncFrame settings = server.sendSettings(snapshot);
        assertEquals(LoginSyncSession.Outcome.PUBLISHED, client.receive(settings).outcome());
        assertEquals(LoginSyncSession.Outcome.DUPLICATE, client.receive(settings).outcome());
        assertEquals(1, applications.get());
    }

    @Test
    void wrongDirectionWinsBeforeOrderOrState() {
        LoginSyncSession client = new LoginSyncSession(LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS);
        LoginSyncFrame wrongDirection = new LoginSyncFrame(
            LoginSyncFrame.Direction.CLIENT_TO_SERVER,
            LoginSyncFrame.Type.SETTINGS,
            TOKEN,
            LoginSettingsSnapshotCodec.encode(snapshot("direction")));

        assertEquals(LoginSyncSession.RejectionReason.WRONG_DIRECTION,
            client.receive(wrongDirection).rejectionReason().orElseThrow());
    }

    @Test
    void conflictDisconnectAndRebindClearPublicationExactlyOnceAndConflictCloses() {
        AtomicInteger cleared = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS, ignored -> { }, cleared::incrementAndGet);
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame hello = client.start(TOKEN);
        LoginSyncFrame serverHello = server.receive(hello).response().orElseThrow();
        client.receive(serverHello);
        LoginSettingsSnapshot first = snapshot("first");
        LoginSettingsSnapshot second = snapshot("second");
        client.receive(server.sendSettings(first));

        assertEquals(LoginSyncSession.Outcome.CONFLICT,
            client.receive(server.sendSettings(second)).outcome());
        client.disconnect();
        client.rebind();
        assertEquals(LoginSyncSession.State.CLOSED, client.state());
        assertEquals(1, cleared.get());
    }

    @Test
    void disconnectAndRebindEachClearPublishedStateExactlyOnce() {
        AtomicInteger cleared = new AtomicInteger();
        LoginSyncSession disconnected = publishedClient(cleared);

        disconnected.disconnect();
        disconnected.disconnect();
        assertTrue(disconnected.publishedSnapshot().isEmpty());
        assertEquals(1, cleared.get());

        LoginSyncSession rebound = publishedClient(cleared);
        rebound.rebind();
        rebound.rebind();
        assertTrue(rebound.publishedSnapshot().isEmpty());
        assertEquals(2, cleared.get());
    }

    @Test
    void applicationFailureLeavesReadyAndRetriesWithoutPartialPublication() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<LoginSettingsSnapshot> published = new AtomicReference<>();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            snapshot -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("try again");
                }
                published.set(snapshot);
            }, () -> { });
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame hello = client.start(TOKEN);
        client.receive(server.receive(hello).response().orElseThrow());
        LoginSettingsSnapshot snapshot = snapshot("retry");
        LoginSyncFrame settings = server.sendSettings(snapshot);

        assertEquals(LoginSyncSession.Outcome.APPLICATION_FAILED, client.receive(settings).outcome());
        assertEquals(LoginSyncSession.State.READY, client.state());
        assertNull(published.get());
        assertEquals(LoginSyncSession.Outcome.PUBLISHED, client.receive(settings).outcome());
        assertEquals(snapshot, published.get());
    }

    @Test
    void applicationCallbackReentryFailsClosedWithoutDoublePublication() {
        AtomicReference<LoginSyncSession> reference = new AtomicReference<>();
        AtomicReference<LoginSyncSession.ReceiveResult> nested = new AtomicReference<>();
        AtomicInteger applications = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            candidate -> {
                applications.incrementAndGet();
                nested.set(reference.get().receive(
                    LoginSyncFrame.settings(TOKEN, snapshot("nested"))));
            }, () -> { });
        reference.set(client);
        LoginSyncSession server = new LoginSyncSession(LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame hello = client.start(TOKEN);
        client.receive(server.receive(hello).response().orElseThrow());

        LoginSyncSession.ReceiveResult outer = client.receive(
            server.sendSettings(snapshot("outer")));

        assertEquals(LoginSyncSession.Outcome.REJECTED, outer.outcome());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            outer.rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.Outcome.REJECTED, nested.get().outcome());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            nested.get().rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.CLOSED, client.state());
        assertTrue(client.publishedSnapshot().isEmpty());
        assertEquals(1, applications.get());
    }

    @Test
    void reentrantCloseDisconnectAndRebindCannotPublishAfterCleanup() {
        assertReentrantTeardownDoesNotPublish(LoginSyncSession::close);
        assertReentrantTeardownDoesNotPublish(LoginSyncSession::disconnect);
        assertReentrantTeardownDoesNotPublish(LoginSyncSession::rebind);
    }

    @Test
    void lifecycleTeardownMayRunOnAnotherThreadAndInvalidatesTheSession() throws Exception {
        assertNonOwnerTeardown(LoginSyncSession::close);
        assertNonOwnerTeardown(LoginSyncSession::disconnect);
        assertNonOwnerTeardown(LoginSyncSession::rebind);
    }

    @Test
    void startReceiveAndSendRemainOwnerConfined() throws Exception {
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS);
        assertNonOwnerRejected(() -> client.start(TOKEN));
        assertEquals(LoginSyncSession.State.NEW, client.state());

        LoginSyncFrame clientHello = client.start(TOKEN);
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        server.receive(clientHello);
        assertNonOwnerRejected(() -> client.receive(
            LoginSyncFrame.serverHello(new HandshakeHello(TOKEN, CAPABILITIES))));
        assertNonOwnerRejected(() -> server.sendSettings(snapshot("owner")));
        assertEquals(LoginSyncSession.State.HELLO_SENT, client.state());
        assertEquals(LoginSyncSession.State.READY, server.state());
    }

    private static void assertNonOwnerRejected(Runnable operation) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                operation.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        other.start();
        other.join();

        assertTrue(thrown.get() instanceof IllegalStateException);
        assertTrue(thrown.get().getMessage().contains("non-owner"));
    }

    private static void assertNonOwnerTeardown(Consumer<LoginSyncSession> teardown)
        throws InterruptedException {
        AtomicInteger cleared = new AtomicInteger();
        LoginSyncSession session = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            ignored -> { }, cleared::incrementAndGet);
        session.start(TOKEN);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                teardown.accept(session);
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        other.start();
        other.join();

        assertNull(thrown.get());
        assertEquals(LoginSyncSession.State.CLOSED, session.state());
        assertEquals(HandshakeSession.State.CLOSED, session.handshakeState());
        assertTrue(session.connectionToken().isEmpty());
        assertTrue(session.localHello().isEmpty());
        assertEquals(1, cleared.get());
        session.close();
        session.disconnect();
        session.rebind();
        assertEquals(1, cleared.get());
    }

    @Test
    void explicitClientTokensArePreserved() {
        LoginSyncSession first = new LoginSyncSession(LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS);
        LoginSyncSession second = new LoginSyncSession(LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS);
        assertEquals(TOKEN, first.start(TOKEN).connectionToken());
        UUID otherToken = UUID.fromString("00000000-0000-0000-0000-000000000012");
        assertEquals(otherToken, second.start(otherToken).connectionToken());
    }

    private static LoginSettingsSnapshot snapshot(String name) {
        return new LoginSettingsSnapshot(
            name, 1, true, false, false, 3, 10,
            "betterquesting:textures/gui/default_title.png", 0.5F, 0F, -128, 0);
    }

    private static LoginSyncSession publishedClient(AtomicInteger cleared) {
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            ignored -> { }, cleared::incrementAndGet);
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame hello = client.start(TOKEN);
        client.receive(server.receive(hello).response().orElseThrow());
        client.receive(server.sendSettings(snapshot("published")));
        return client;
    }

    private static void assertReentrantTeardownDoesNotPublish(
        Consumer<LoginSyncSession> teardown
    ) {
        AtomicReference<LoginSyncSession> reference = new AtomicReference<>();
        AtomicInteger cleared = new AtomicInteger();
        LoginSyncSession client = new LoginSyncSession(
            LoginSyncSession.Role.CLIENT, CAPABILITIES, LIMITS,
            ignored -> teardown.accept(reference.get()), cleared::incrementAndGet);
        reference.set(client);
        LoginSyncSession server = new LoginSyncSession(
            LoginSyncSession.Role.SERVER, CAPABILITIES, LIMITS);
        LoginSyncFrame hello = client.start(TOKEN);
        client.receive(server.receive(hello).response().orElseThrow());

        LoginSyncSession.ReceiveResult result =
            client.receive(server.sendSettings(snapshot("reentrant-close")));

        assertEquals(LoginSyncSession.Outcome.REJECTED, result.outcome());
        assertEquals(LoginSyncSession.RejectionReason.CLOSED,
            result.rejectionReason().orElseThrow());
        assertEquals(LoginSyncSession.State.CLOSED, client.state());
        assertTrue(client.publishedSnapshot().isEmpty());
        assertEquals(1, cleared.get());
        client.close();
        client.disconnect();
        client.rebind();
        assertEquals(1, cleared.get());
    }
}
