package com.github.postyizhan.betterquesting.network.sync;

import com.github.postyizhan.betterquesting.network.handshake.HandshakeCapabilities;
import com.github.postyizhan.betterquesting.network.handshake.HandshakeLimits;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owns sessions associated with one client or server lifecycle. */
public final class LoginSyncConnectionOwner implements AutoCloseable {
    @FunctionalInterface
    public interface SessionFactory {
        LoginSyncSession create(LoginSyncSession.Role role, Object handler);
    }

    private final Object lifecycleLock = new Object();
    private final LoginSyncSession.Role role;
    private final SessionFactory sessionFactory;
    private final IdentityHashMap<Object, Binding> bindings = new IdentityHashMap<>();
    private Transition transition;
    private boolean closed;

    public LoginSyncConnectionOwner(
        LoginSyncSession.Role role,
        SessionFactory sessionFactory
    ) {
        this.role = Objects.requireNonNull(role, "role");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    public LoginSyncConnectionOwner(
        LoginSyncSession.Role role,
        HandshakeCapabilities capabilities,
        HandshakeLimits limits
    ) {
        this(role, (sessionRole, ignoredHandler) ->
            new LoginSyncSession(sessionRole, capabilities, limits));
    }

    public LoginSyncSession.Role role() {
        return role;
    }

    public LoginSyncSession bind(Object handler) {
        requireRole(LoginSyncSession.Role.CLIENT);
        return bind(null, handler, false);
    }

    public LoginSyncSession bind(Object serverOwner, Object handler) {
        requireRole(LoginSyncSession.Role.SERVER);
        return bind(serverOwner, handler, false);
    }

    public LoginSyncSession rebind(Object handler) {
        requireRole(LoginSyncSession.Role.CLIENT);
        return bind(null, handler, true);
    }

    public LoginSyncSession rebind(Object serverOwner, Object handler) {
        requireRole(LoginSyncSession.Role.SERVER);
        return bind(serverOwner, handler, true);
    }

    public Optional<LoginSyncSession.ReceiveResult> receive(
        Object handler,
        LoginSyncFrame frame
    ) {
        if (handler == null) {
            return Optional.empty();
        }
        Binding binding;
        synchronized (lifecycleLock) {
            binding = bindings.get(handler);
            if (closed || binding == null) {
                return Optional.empty();
            }
        }
        LoginSyncSession.ReceiveResult result = binding.session.receive(frame);
        synchronized (lifecycleLock) {
            if (binding.session.isClosed() && bindings.get(handler) == binding) {
                bindings.remove(handler);
            }
        }
        return Optional.of(result);
    }

    public Optional<LoginSyncSession.ReceiveResult> receiveEncoded(
        Object handler,
        byte[] encoded
    ) {
        if (handler == null) {
            return Optional.empty();
        }
        Binding binding;
        synchronized (lifecycleLock) {
            binding = bindings.get(handler);
            if (closed || binding == null) {
                return Optional.empty();
            }
        }
        LoginSyncSession.ReceiveResult result = binding.session.receiveEncoded(encoded);
        synchronized (lifecycleLock) {
            if (binding.session.isClosed() && bindings.get(handler) == binding) {
                bindings.remove(handler);
            }
        }
        return Optional.of(result);
    }

    public boolean unbind(Object handler) {
        Objects.requireNonNull(handler, "handler");
        Binding removed;
        boolean cancelled = false;
        synchronized (lifecycleLock) {
            removed = bindings.remove(handler);
            if (transition != null && transition.handler == handler) {
                transition.cancelled = true;
                cancelled = true;
            }
        }
        close(removed);
        return removed != null || cancelled;
    }

    public boolean unbind(Object handler, LoginSyncSession expected) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(expected, "expected");
        Binding removed = null;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            if (binding != null && binding.session == expected) {
                removed = bindings.remove(handler);
            }
            if (transition != null && transition.handler == handler
                && transition.replaced.stream().anyMatch(replaced -> replaced.session == expected)) {
                transition.cancelled = true;
            }
        }
        close(removed);
        return removed != null;
    }

    public boolean unbind(Object serverOwner, Object handler) {
        requireRole(LoginSyncSession.Role.SERVER);
        Objects.requireNonNull(serverOwner, "serverOwner");
        Objects.requireNonNull(handler, "handler");
        Binding removed = null;
        boolean cancelled = false;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            if (binding != null && binding.serverOwner == serverOwner) {
                removed = bindings.remove(handler);
            }
            if (transition != null && transition.handler == handler
                && transition.serverOwner == serverOwner) {
                transition.cancelled = true;
                cancelled = true;
            }
        }
        close(removed);
        return removed != null || cancelled;
    }

    public boolean unbind(
        Object serverOwner,
        Object handler,
        LoginSyncSession expected
    ) {
        requireRole(LoginSyncSession.Role.SERVER);
        Objects.requireNonNull(serverOwner, "serverOwner");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(expected, "expected");
        Binding removed = null;
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            if (binding != null && binding.serverOwner == serverOwner
                && binding.session == expected) {
                removed = bindings.remove(handler);
            }
        }
        close(removed);
        return removed != null;
    }

    public int closeAll(Object serverOwner) {
        requireRole(LoginSyncSession.Role.SERVER);
        Objects.requireNonNull(serverOwner, "serverOwner");
        List<Binding> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (transition != null && transition.serverOwner == serverOwner) {
                transition.cancelled = true;
            }
            removeServerBindings(serverOwner, removed);
        }
        close(removed);
        return removed.size();
    }

    public int size() {
        synchronized (lifecycleLock) {
            return bindings.size();
        }
    }

    public boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    public Optional<LoginSyncSession> current(Object handler) {
        if (handler == null) {
            return Optional.empty();
        }
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            return closed || binding == null
                ? Optional.empty()
                : Optional.of(binding.session);
        }
    }

    public Optional<LoginSyncSession> current(Object serverOwner, Object handler) {
        requireRole(LoginSyncSession.Role.SERVER);
        if (serverOwner == null || handler == null) {
            return Optional.empty();
        }
        synchronized (lifecycleLock) {
            Binding binding = bindings.get(handler);
            return closed || binding == null || binding.serverOwner != serverOwner
                ? Optional.empty()
                : Optional.of(binding.session);
        }
    }

    @Override
    public void close() {
        List<Binding> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (closed && bindings.isEmpty() && transition == null) {
                return;
            }
            closed = true;
            if (transition != null) {
                transition.cancelled = true;
            }
            removed.addAll(bindings.values());
            bindings.clear();
        }
        close(removed);
    }

    private LoginSyncSession bind(
        Object serverOwner,
        Object handler,
        boolean forceRebind
    ) {
        Objects.requireNonNull(handler, "handler");
        if (role == LoginSyncSession.Role.SERVER) {
            Objects.requireNonNull(serverOwner, "serverOwner");
        }

        List<Binding> removed = new ArrayList<>();
        Transition next;
        synchronized (lifecycleLock) {
            waitForTransition();
            ensureOpen();
            Binding existing = bindings.get(handler);
            if (!forceRebind && existing != null) {
                if (role == LoginSyncSession.Role.CLIENT || existing.serverOwner == serverOwner) {
                    return existing.session;
                }
                throw new IllegalStateException(
                    "handler is already bound to a different server owner");
            }

            if (role == LoginSyncSession.Role.CLIENT) {
                removed.addAll(bindings.values());
                bindings.clear();
            } else if (existing != null) {
                bindings.remove(handler);
                removed.add(existing);
            }

            next = new Transition(serverOwner, handler, removed);
            transition = next;
        }

        return createBinding(next, removed);
    }

    private LoginSyncSession createBinding(Transition next, List<Binding> removed) {
        LoginSyncSession candidate = null;
        boolean published = false;
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            close(removed);
            synchronized (lifecycleLock) {
                ensureTransitionActive(next);
            }
            candidate = Objects.requireNonNull(
                sessionFactory.create(role, next.handler), "sessionFactory returned null");
            if (candidate.role() != role) {
                throw new IllegalStateException(
                    "sessionFactory returned a session for the wrong role");
            }
            if (candidate.isClosed()) {
                throw new IllegalStateException(
                    "sessionFactory returned a closed session");
            }
            synchronized (lifecycleLock) {
                if (transition == next && !next.cancelled && !closed) {
                    bindings.put(next.handler, new Binding(next.serverOwner, candidate));
                    published = true;
                    transition = null;
                    lifecycleLock.notifyAll();
                }
            }
            if (!published) {
                throw new IllegalStateException("login sync binding was cancelled");
            }
            return candidate;
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
        } catch (Error failure) {
            errorFailure = failure;
        } finally {
            if (!published) {
                try {
                    close(candidate);
                } finally {
                    synchronized (lifecycleLock) {
                        if (transition == next) {
                            transition = null;
                            lifecycleLock.notifyAll();
                        }
                    }
                }
            }
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
        throw runtimeFailure;
    }

    private void waitForTransition() {
        boolean interrupted = false;
        while (transition != null) {
            if (transition.creator == Thread.currentThread()) {
                throw new IllegalStateException("login sync binding re-entered from its factory");
            }
            try {
                lifecycleLock.wait();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureTransitionActive(Transition candidate) {
        if (transition != candidate || candidate.cancelled || closed) {
            throw new IllegalStateException("login sync binding was cancelled");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("login sync connection owner is closed");
        }
    }

    private void requireRole(LoginSyncSession.Role expected) {
        if (role != expected) {
            throw new IllegalStateException("operation is only valid for a " + expected + " owner");
        }
    }

    private void removeServerBindings(Object serverOwner, List<Binding> removed) {
        var iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            Binding binding = iterator.next().getValue();
            if (binding.serverOwner == serverOwner) {
                removed.add(binding);
                iterator.remove();
            }
        }
    }

    private static void close(Binding binding) {
        if (binding != null) {
            binding.session.close();
        }
    }

    private static void close(LoginSyncSession session) {
        if (session != null) {
            session.close();
        }
    }

    private static void close(List<Binding> bindings) {
        Throwable failure = null;
        for (Binding binding : bindings) {
            try {
                close(binding);
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
    }

    private static final class Binding {
        private final Object serverOwner;
        private final LoginSyncSession session;

        private Binding(Object serverOwner, LoginSyncSession session) {
            this.serverOwner = serverOwner;
            this.session = session;
        }
    }

    private static final class Transition {
        private final Object serverOwner;
        private final Object handler;
        private final Thread creator = Thread.currentThread();
        private final List<Binding> replaced;
        private boolean cancelled;

        private Transition(Object serverOwner, Object handler, List<Binding> replaced) {
            this.serverOwner = serverOwner;
            this.handler = handler;
            this.replaced = List.copyOf(replaced);
        }
    }
}
