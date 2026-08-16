package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeHello;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeSession;
import java.util.ArrayList;
import java.util.List;
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
    private boolean clearPending;
    private boolean applying;
    private final List<Runnable> closeHooks = new ArrayList<>();

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
        ReceiveStep step;
        CloseActions closeActions;
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                step = ReceiveStep.immediate(rejected(RejectionReason.CLOSED));
            } else if (frame == null) {
                step = ReceiveStep.immediate(rejected(RejectionReason.MALFORMED));
            } else if (frame.payload().length > LoginSyncFrame.MAX_PAYLOAD_BYTES) {
                step = ReceiveStep.immediate(rejected(RejectionReason.OVERSIZED));
            } else {
                step = role == Role.SERVER ? receiveOnServer(frame) : receiveOnClient(frame);
            }
            closeActions = drainCloseActionsLocked();
        }
        runCloseActions(closeActions);
        return step.applicationCandidate() == null
            ? step.result()
            : applySettings(step.applicationCandidate());
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

    /** Adds connection-owned cleanup that runs exactly once when this session closes. */
    public void addCloseHook(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        boolean runNow;
        synchronized (lifecycleLock) {
            runNow = state == State.CLOSED && !applying;
            if (!runNow) {
                closeHooks.add(hook);
            }
        }
        if (runNow) {
            runCloseHook(hook);
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
        CloseActions closeActions;
        synchronized (lifecycleLock) {
            closeLocked();
            closeActions = drainCloseActionsLocked();
        }
        runCloseActions(closeActions);
    }

    private static void runCloseHook(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException | Error ignored) {
            // Teardown must still invalidate the connection if a cleanup consumer fails.
        }
    }

    private ReceiveStep receiveOnServer(LoginSyncFrame frame) {
        if (frame.direction() != LoginSyncFrame.Direction.CLIENT_TO_SERVER) {
            return ReceiveStep.immediate(rejected(RejectionReason.WRONG_DIRECTION));
        }
        if (frame.type() != LoginSyncFrame.Type.CLIENT_HELLO) {
            return ReceiveStep.immediate(rejected(RejectionReason.OUT_OF_ORDER));
        }

        Optional<HandshakeHello> decodedHello = frame.hello();
        if (decodedHello.isEmpty()) {
            return ReceiveStep.immediate(rejected(RejectionReason.MALFORMED));
        }
        HandshakeHello remoteHello = decodedHello.orElseThrow();
        if (!frame.connectionToken().equals(remoteHello.connectionToken())) {
            return ReceiveStep.immediate(rejected(RejectionReason.WRONG_TOKEN));
        }

        if (state == State.NEW) {
            connectionToken = remoteHello.connectionToken();
            localHello = handshake.start(connectionToken);
            HandshakeSession.ReceiveResult handshakeResult = handshake.receive(remoteHello);
            if (handshakeResult.outcome() != HandshakeSession.ReceiveOutcome.READY) {
                return ReceiveStep.immediate(handshakeRejected());
            }
            state = State.READY;
            serverHelloFrame = LoginSyncFrame.serverHello(localHello);
            return ReceiveStep.immediate(accepted(serverHelloFrame));
        }
        if (state == State.FAILED) {
            return ReceiveStep.immediate(rejected(RejectionReason.HANDSHAKE_REJECTED));
        }
        if (!connectionToken.equals(remoteHello.connectionToken())) {
            return ReceiveStep.immediate(rejected(RejectionReason.WRONG_TOKEN));
        }
        if (connectionToken.equals(remoteHello.connectionToken())
            && localHello != null && handshake.receive(remoteHello).outcome()
                == HandshakeSession.ReceiveOutcome.DUPLICATE) {
            return ReceiveStep.immediate(result(Outcome.DUPLICATE, null, serverHelloFrame));
        }
        return ReceiveStep.immediate(conflict());
    }

    private ReceiveStep receiveOnClient(LoginSyncFrame frame) {
        if (frame.direction() != LoginSyncFrame.Direction.SERVER_TO_CLIENT) {
            return ReceiveStep.immediate(rejected(RejectionReason.WRONG_DIRECTION));
        }
        if (frame.type() != LoginSyncFrame.Type.SERVER_HELLO
            && frame.type() != LoginSyncFrame.Type.SETTINGS) {
            return ReceiveStep.immediate(rejected(RejectionReason.OUT_OF_ORDER));
        }
        if (state == State.NEW) {
            return ReceiveStep.immediate(rejected(RejectionReason.OUT_OF_ORDER));
        }
        if (state == State.FAILED) {
            return ReceiveStep.immediate(rejected(RejectionReason.HANDSHAKE_REJECTED));
        }
        if (connectionToken == null || !connectionToken.equals(frame.connectionToken())) {
            return ReceiveStep.immediate(rejected(RejectionReason.WRONG_TOKEN));
        }

        if (frame.type() == LoginSyncFrame.Type.SERVER_HELLO) {
            return ReceiveStep.immediate(receiveServerHello(frame));
        }
        if (state != State.READY && state != State.PUBLISHED) {
            return ReceiveStep.immediate(rejected(RejectionReason.OUT_OF_ORDER));
        }
        Optional<LoginSettingsSnapshot> settings = frame.settings();
        if (settings.isEmpty()) {
            return ReceiveStep.immediate(rejected(RejectionReason.INVALID_SETTINGS));
        }
        return prepareSettings(settings.orElseThrow());
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

    private ReceiveStep prepareSettings(LoginSettingsSnapshot candidate) {
        if (applying) {
            closeLocked();
            return ReceiveStep.immediate(rejected(RejectionReason.CLOSED));
        }
        if (publishedSnapshot != null) {
            return ReceiveStep.immediate(publishedSnapshot.equals(candidate)
                ? result(Outcome.DUPLICATE, null, null)
                : conflict());
        }
        applying = true;
        return ReceiveStep.application(candidate);
    }

    private ReceiveResult applySettings(LoginSettingsSnapshot candidate) {
        CloseActions closeActions = null;
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                applying = false;
                closeActions = drainCloseActionsLocked();
            }
        }
        if (closeActions != null) {
            runCloseActions(closeActions);
            return rejected(RejectionReason.CLOSED);
        }
        try {
            application.apply(candidate);
        } catch (RuntimeException applicationFailure) {
            return finishSettingsApplication(candidate, false);
        } catch (Error applicationFailure) {
            CloseActions errorCloseActions;
            synchronized (lifecycleLock) {
                applying = false;
                errorCloseActions = drainCloseActionsLocked();
            }
            runCloseActions(errorCloseActions);
            throw applicationFailure;
        }
        return finishSettingsApplication(candidate, true);
    }

    private ReceiveResult finishSettingsApplication(
        LoginSettingsSnapshot candidate,
        boolean succeeded
    ) {
        ReceiveResult result;
        CloseActions closeActions;
        synchronized (lifecycleLock) {
            applying = false;
            if (state == State.CLOSED) {
                result = rejected(RejectionReason.CLOSED);
            } else if (!succeeded) {
                result = result(Outcome.APPLICATION_FAILED, null, null);
            } else if (state != State.READY || publishedSnapshot != null) {
                closeLocked();
                result = rejected(RejectionReason.CLOSED);
            } else {
                publishedSnapshot = candidate;
                state = State.PUBLISHED;
                result = result(Outcome.PUBLISHED, null, null);
            }
            closeActions = drainCloseActionsLocked();
        }
        runCloseActions(closeActions);
        return result;
    }

    private ReceiveResult conflict() {
        closeLocked();
        return result(Outcome.CONFLICT, RejectionReason.CONFLICT, null);
    }

    private ReceiveResult handshakeRejected() {
        closeLocked();
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

    private void closeLocked() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        handshake.close();
        localHello = null;
        connectionToken = null;
        serverHelloFrame = null;
        publishedSnapshot = null;
        if (!clearCalled) {
            clearCalled = true;
            clearPending = true;
        }
    }

    private CloseActions drainCloseActionsLocked() {
        if (applying) {
            return new CloseActions(false, List.of());
        }
        boolean clear = clearPending;
        clearPending = false;
        List<Runnable> hooks = state == State.CLOSED ? List.copyOf(closeHooks) : List.of();
        if (state == State.CLOSED) {
            closeHooks.clear();
        }
        return new CloseActions(clear, hooks);
    }

    private void runCloseActions(CloseActions closeActions) {
        if (closeActions.clearPublication()) {
            runClearPublication();
        }
        for (Runnable hook : closeActions.hooks()) {
            runCloseHook(hook);
        }
    }

    private void runClearPublication() {
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

    private record ReceiveStep(
        ReceiveResult result,
        LoginSettingsSnapshot applicationCandidate
    ) {
        private static ReceiveStep immediate(ReceiveResult result) {
            return new ReceiveStep(Objects.requireNonNull(result, "result"), null);
        }

        private static ReceiveStep application(LoginSettingsSnapshot candidate) {
            return new ReceiveStep(null, Objects.requireNonNull(candidate, "candidate"));
        }
    }

    private record CloseActions(boolean clearPublication, List<Runnable> hooks) {
        private CloseActions {
            Objects.requireNonNull(hooks, "hooks");
        }
    }
}
