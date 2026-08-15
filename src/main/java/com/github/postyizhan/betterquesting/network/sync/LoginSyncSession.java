package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeSession;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-confined login handshake and settings coordinator. It has no transport or identity
 * dependency: the token only correlates frames belonging to this connection.
 */
public final class LoginSyncSession implements AutoCloseable {
    public enum Role {
        CLIENT,
        SERVER
    }

    public enum State {
        NEW,
        HELLO_SENT,
        READY,
        PUBLISHED,
        FAILED,
        CLOSED
    }

    public enum Outcome {
        ACCEPTED,
        PUBLISHED,
        DUPLICATE,
        APPLICATION_FAILED,
        REJECTED,
        CONFLICT
    }

    public enum RejectionReason {
        MALFORMED,
        OVERSIZED,
        WRONG_DIRECTION,
        OUT_OF_ORDER,
        WRONG_TOKEN,
        HANDSHAKE_REJECTED,
        INVALID_SETTINGS,
        CLOSED,
        CONFLICT
    }

    public record ReceiveResult(
        Outcome outcome,
        Optional<RejectionReason> rejectionReason,
        Optional<LoginSyncFrame> response
    ) {
        public ReceiveResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            Objects.requireNonNull(response, "response");
        }

        public Optional<RejectionReason> reason() {
            return rejectionReason;
        }

        public Optional<LoginSyncFrame> frame() {
            return response;
        }
    }

    private final Thread owner;
    private final Object lifecycleLock = new Object();
    private final Role role;
    private final HandshakeSession handshake;
    private final LoginSettingsSyncState.SnapshotApplication application;
    private final Runnable clearPublication;

    private State state = State.NEW;
    private HandshakeHello localHello;
    private UUID connectionToken;
    private LoginSyncFrame serverHelloFrame;
    private LoginSettingsSnapshot publishedSnapshot;
    private boolean clearCalled;
    private boolean applying;

    public LoginSyncSession(
        Role role,
        HandshakeCapabilities localCapabilities,
        HandshakeLimits limits
    ) {
        this(role, localCapabilities, limits, ignored -> { }, () -> { });
    }

    public LoginSyncSession(
        Role role,
        HandshakeCapabilities localCapabilities,
        HandshakeLimits limits,
        LoginSettingsSyncState.SnapshotApplication application
    ) {
        this(role, localCapabilities, limits, application, () -> { });
    }

    public LoginSyncSession(
        Role role,
        HandshakeCapabilities localCapabilities,
        HandshakeLimits limits,
        LoginSettingsSyncState.SnapshotApplication application,
        Runnable clearPublication
    ) {
        this.owner = Thread.currentThread();
        this.role = Objects.requireNonNull(role, "role");
        Objects.requireNonNull(localCapabilities, "localCapabilities");
        Objects.requireNonNull(limits, "limits");
        this.application = Objects.requireNonNull(application, "application");
        this.clearPublication = Objects.requireNonNull(clearPublication, "clearPublication");
        this.handshake = new HandshakeSession(localCapabilities, limits);
    }

    /** Starts the client side with a fresh connection token. */
    public LoginSyncFrame start() {
        return start(UUID.randomUUID());
    }

    /** Starts the client side with a token established by its connection. */
    public LoginSyncFrame start(UUID token) {
        checkOwner();
        synchronized (lifecycleLock) {
            ensureOpen();
            if (role != Role.CLIENT) {
                throw new IllegalStateException("only the client starts with a hello");
            }
            if (state != State.NEW) {
                throw new IllegalStateException("login sync hello cannot be started in state " + state);
            }
            localHello = handshake.start(Objects.requireNonNull(token, "connectionToken"));
            connectionToken = localHello.connectionToken();
            state = State.HELLO_SENT;
            return LoginSyncFrame.clientHello(localHello);
        }
    }

    public LoginSyncFrame startClientHello() {
        return start();
    }

    public LoginSyncFrame startClientHello(UUID connectionToken) {
        return start(connectionToken);
    }

    /** Returns an immutable server settings frame after both handshake sides are ready. */
    public LoginSyncFrame sendSettings(LoginSettingsSnapshot snapshot) {
        checkOwner();
        synchronized (lifecycleLock) {
            ensureOpen();
            if (role != Role.SERVER || state != State.READY || connectionToken == null) {
                throw new IllegalStateException("settings cannot be sent before the handshake is ready");
            }
            return LoginSyncFrame.settings(
                connectionToken, Objects.requireNonNull(snapshot, "snapshot"));
        }
    }

    public ReceiveResult receiveEncoded(byte[] encoded) {
        checkOwner();
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                return rejected(RejectionReason.CLOSED);
            }
        }
        if (encoded == null) {
            return rejected(RejectionReason.MALFORMED);
        }
        if (encoded.length > LoginSyncFrameCodec.MAX_ENCODED_BYTES) {
            return rejected(RejectionReason.OVERSIZED);
        }
        Optional<LoginSyncFrame> decoded = LoginSyncFrameCodec.decode(encoded);
        return decoded.isEmpty() ? rejected(RejectionReason.MALFORMED) : receive(decoded.orElseThrow());
    }

    public ReceiveResult receive(byte[] encoded) {
        return receiveEncoded(encoded);
    }

    public ReceiveResult receive(LoginSyncFrame frame) {
        checkOwner();
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                return rejected(RejectionReason.CLOSED);
            }
            if (frame == null) {
                return rejected(RejectionReason.MALFORMED);
            }
            if (frame.payload().length > LoginSyncFrame.MAX_PAYLOAD_BYTES) {
                return rejected(RejectionReason.OVERSIZED);
            }

            return role == Role.SERVER ? receiveOnServer(frame) : receiveOnClient(frame);
        }
    }

    public State state() {
        checkOwner();
        synchronized (lifecycleLock) {
            return state;
        }
    }

    public Role role() {
        return role;
    }

    public Optional<UUID> connectionToken() {
        checkOwner();
        synchronized (lifecycleLock) {
            return Optional.ofNullable(connectionToken);
        }
    }

    public Optional<HandshakeHello> localHello() {
        checkOwner();
        synchronized (lifecycleLock) {
            return Optional.ofNullable(localHello);
        }
    }

    public Optional<LoginSettingsSnapshot> publishedSnapshot() {
        checkOwner();
        synchronized (lifecycleLock) {
            return Optional.ofNullable(publishedSnapshot);
        }
    }

    HandshakeSession.State handshakeState() {
        checkOwner();
        synchronized (lifecycleLock) {
            return handshake.state();
        }
    }

    public boolean isClosed() {
        checkOwner();
        synchronized (lifecycleLock) {
            return state == State.CLOSED;
        }
    }

    /** Invalidates this connection; repeated teardown is deliberately idempotent. */
    public void disconnect() {
        close();
    }

    /** Ends this binding. A new connection must use a new LoginSyncSession instance. */
    public void rebind() {
        close();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            handshake.close();
            localHello = null;
            connectionToken = null;
            serverHelloFrame = null;
            publishedSnapshot = null;
            clearOnce();
        }
    }

    private ReceiveResult receiveOnServer(LoginSyncFrame frame) {
        if (frame.direction() != LoginSyncFrame.Direction.CLIENT_TO_SERVER) {
            return rejected(RejectionReason.WRONG_DIRECTION);
        }
        if (frame.type() != LoginSyncFrame.Type.CLIENT_HELLO) {
            return rejected(RejectionReason.OUT_OF_ORDER);
        }

        Optional<HandshakeHello> decodedHello = frame.hello();
        if (decodedHello.isEmpty()) {
            return rejected(RejectionReason.MALFORMED);
        }
        HandshakeHello remoteHello = decodedHello.orElseThrow();
        if (!frame.connectionToken().equals(remoteHello.connectionToken())) {
            return rejected(RejectionReason.WRONG_TOKEN);
        }

        if (state == State.NEW) {
            connectionToken = remoteHello.connectionToken();
            localHello = handshake.start(connectionToken);
            HandshakeSession.ReceiveResult handshakeResult = handshake.receive(remoteHello);
            if (handshakeResult.outcome() != HandshakeSession.ReceiveOutcome.READY) {
                return handshakeRejected();
            }
            state = State.READY;
            serverHelloFrame = LoginSyncFrame.serverHello(localHello);
            return accepted(serverHelloFrame);
        }
        if (state == State.FAILED) {
            return rejected(RejectionReason.HANDSHAKE_REJECTED);
        }
        if (!connectionToken.equals(remoteHello.connectionToken())) {
            return rejected(RejectionReason.WRONG_TOKEN);
        }
        if (connectionToken.equals(remoteHello.connectionToken())
            && localHello != null && handshake.receive(remoteHello).outcome()
                == HandshakeSession.ReceiveOutcome.DUPLICATE) {
            return result(Outcome.DUPLICATE, null, serverHelloFrame);
        }
        return conflict();
    }

    private ReceiveResult receiveOnClient(LoginSyncFrame frame) {
        if (frame.direction() != LoginSyncFrame.Direction.SERVER_TO_CLIENT) {
            return rejected(RejectionReason.WRONG_DIRECTION);
        }
        if (frame.type() != LoginSyncFrame.Type.SERVER_HELLO
            && frame.type() != LoginSyncFrame.Type.SETTINGS) {
            return rejected(RejectionReason.OUT_OF_ORDER);
        }
        if (state == State.NEW) {
            return rejected(RejectionReason.OUT_OF_ORDER);
        }
        if (state == State.FAILED) {
            return rejected(RejectionReason.HANDSHAKE_REJECTED);
        }
        if (connectionToken == null || !connectionToken.equals(frame.connectionToken())) {
            return rejected(RejectionReason.WRONG_TOKEN);
        }

        if (frame.type() == LoginSyncFrame.Type.SERVER_HELLO) {
            return receiveServerHello(frame);
        }
        if (state != State.READY && state != State.PUBLISHED) {
            return rejected(RejectionReason.OUT_OF_ORDER);
        }
        Optional<LoginSettingsSnapshot> settings = frame.settings();
        if (settings.isEmpty()) {
            return rejected(RejectionReason.INVALID_SETTINGS);
        }
        return receiveSettings(settings.orElseThrow());
    }

    private ReceiveResult receiveServerHello(LoginSyncFrame frame) {
        Optional<HandshakeHello> decoded = frame.hello();
        if (decoded.isEmpty()) {
            return rejected(RejectionReason.MALFORMED);
        }
        HandshakeHello remoteHello = decoded.orElseThrow();
        if (!connectionToken.equals(remoteHello.connectionToken())) {
            return rejected(RejectionReason.WRONG_TOKEN);
        }
        if (state == State.READY || state == State.PUBLISHED) {
            if (localHello != null
                && handshake.receive(remoteHello).outcome()
                    == HandshakeSession.ReceiveOutcome.DUPLICATE) {
                return result(Outcome.DUPLICATE, null, null);
            }
            return conflict();
        }
        if (state != State.HELLO_SENT) {
            return rejected(RejectionReason.OUT_OF_ORDER);
        }
        HandshakeSession.ReceiveResult handshakeResult = handshake.receive(remoteHello);
        if (handshakeResult.outcome() != HandshakeSession.ReceiveOutcome.READY) {
            return handshakeRejected();
        }
        state = State.READY;
        return accepted(null);
    }

    private ReceiveResult receiveSettings(LoginSettingsSnapshot candidate) {
        if (applying) {
            close();
            return rejected(RejectionReason.CLOSED);
        }
        if (publishedSnapshot != null) {
            return publishedSnapshot.equals(candidate) ? result(Outcome.DUPLICATE, null, null) : conflict();
        }
        applying = true;
        try {
            application.apply(candidate);
        } catch (RuntimeException applicationFailure) {
            return state == State.CLOSED
                ? rejected(RejectionReason.CLOSED)
                : result(Outcome.APPLICATION_FAILED, null, null);
        } finally {
            applying = false;
        }
        if (state != State.READY || publishedSnapshot != null) {
            if (state != State.CLOSED) {
                close();
            }
            return rejected(RejectionReason.CLOSED);
        }
        publishedSnapshot = candidate;
        state = State.PUBLISHED;
        return result(Outcome.PUBLISHED, null, null);
    }

    private ReceiveResult conflict() {
        close();
        return result(Outcome.CONFLICT, RejectionReason.CONFLICT, null);
    }

    private ReceiveResult handshakeRejected() {
        close();
        return rejected(RejectionReason.HANDSHAKE_REJECTED);
    }

    private ReceiveResult accepted(LoginSyncFrame response) {
        return result(Outcome.ACCEPTED, null, response);
    }

    private ReceiveResult rejected(RejectionReason reason) {
        return result(Outcome.REJECTED, reason, null);
    }

    private static ReceiveResult result(
        Outcome outcome,
        RejectionReason reason,
        LoginSyncFrame response
    ) {
        return new ReceiveResult(
            outcome,
            Optional.ofNullable(reason),
            Optional.ofNullable(response));
    }

    private void clearOnce() {
        if (clearCalled) {
            return;
        }
        clearCalled = true;
        try {
            clearPublication.run();
        } catch (RuntimeException ignored) {
            // Teardown must still invalidate the connection if a consumer fails while clearing.
        }
    }

    private void ensureOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("login sync session is closed");
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("login sync session accessed from a non-owner thread");
        }
    }
}
