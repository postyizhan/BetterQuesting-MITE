package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.identity.CorruptIdentityMappingException;
import com.github.postyizhan.betterquesting.core.identity.IdentityAuditReport;
import com.github.postyizhan.betterquesting.core.identity.IdentityRecordRejection;
import com.github.postyizhan.betterquesting.core.identity.PersistentPlayerIdentityService;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/**
 * Binds one server session's identity service to that session's world storage.
 *
 * <p>The held storage is world-lifetime bound, so this holder is explicitly not a
 * cross-world cache: {@link #bind(MinecraftServer)} resolves storage fresh per session,
 * {@link #retire(MinecraftServer)} drops it for the matching owner, and
 * {@link #current(MinecraftServer)} refuses to serve a
 * service bound to a different server instance. An integrated server creates a new server and save
 * handler each time a world is entered, so without the identity check a second world would keep
 * writing the first world's directory (docs/handoff.md section 4.2).
 * A different owner cannot bind until the active owner retires, so a stale startup callback cannot
 * replace or clear a newer session's service.
 *
 * <p>{@code bind} must run after world load. Resolving before {@code worldServers} is populated
 * yields a permanently disabled storage with no retry path, which is why this is wired to the
 * server-started notification rather than to mod construction.
 *
 * <p>A corrupt mapping file makes binding fail. Serving identities with an empty mapping set would
 * let isolated legacy saves be re-claimed by derived identities.
 *
 * <p>The owner/storage lifecycle is covered through a package-private pure-JVM seam. Actual
 * {@code MinecraftServer} storage resolution and callback ordering still require a runtime smoke.
 */
public final class ServerIdentityContext {
    private static Object boundOwner;
    private static PersistentPlayerIdentityService identities;

    private ServerIdentityContext() {
    }

    public static synchronized PersistentPlayerIdentityService bind(MinecraftServer server) throws IOException {
        Objects.requireNonNull(server, "server");
        requireBindableOwner(server);

        MiteWorldStorage storage = MiteWorldStorage.resolve(server);
        if (!storage.isAvailable()) {
            throw new IOException("BetterQuesting identity storage is unavailable: "
                + storage.getDisabledReason().orElse("unknown reason"));
        }
        return bind((Object) server, new DirectoryWorldStorage(
            storage.getDataDirectory().orElseThrow(
                () -> new IOException("BetterQuesting identity storage has no data directory"))));
    }

    static synchronized PersistentPlayerIdentityService bind(Object owner,
        DirectoryWorldStorage storage) throws IOException {
        Objects.requireNonNull(owner, "owner");
        requireBindableOwner(owner);
        Objects.requireNonNull(storage, "storage");
        clearBinding();

        PersistentPlayerIdentityService service = new PersistentPlayerIdentityService(storage);
        try {
            IdentityAuditReport report = service.loadFromStorage();
            logRejections(report);
        } catch (CorruptIdentityMappingException corrupt) {
            for (IdentityRecordRejection rejection : corrupt.rejections()) {
                BetterQuestingMod.LOGGER.error("Rejected identity mapping {}", rejection);
            }
            throw corrupt;
        }

        BetterQuestingMod.LOGGER.info("Loaded {} persisted BetterQuesting identity mappings",
            service.legacyMappingsSnapshot().size());
        boundOwner = owner;
        identities = service;
        return service;
    }

    /** Returns the service only when it belongs to {@code server}, so a stale binding is never used. */
    public static Optional<PersistentPlayerIdentityService> current(MinecraftServer server) {
        return current((Object) server);
    }

    static synchronized Optional<PersistentPlayerIdentityService> current(Object owner) {
        if (owner == null || boundOwner != owner) {
            return Optional.empty();
        }
        return Optional.ofNullable(identities);
    }

    public static void retire(MinecraftServer server) {
        retire((Object) server);
    }

    static synchronized void retire(Object owner) {
        if (boundOwner == owner) {
            clearBinding();
        }
    }

    private static void clearBinding() {
        boundOwner = null;
        identities = null;
    }

    private static void requireBindableOwner(Object owner) {
        if (boundOwner != null && boundOwner != owner) {
            throw new IllegalStateException(
                "BetterQuesting identity service is already bound to a different server owner");
        }
    }

    private static void logRejections(IdentityAuditReport report) {
        for (IdentityRecordRejection rejection : report.rejections()) {
            BetterQuestingMod.LOGGER.warn("Rejected identity audit {}", rejection);
        }
    }
}
