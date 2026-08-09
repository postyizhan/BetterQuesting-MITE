package com.github.postyizhan.betterquesting.platform.fml;

import com.github.postyizhan.betterquesting.BetterQuestingMod;
import com.github.postyizhan.betterquesting.core.identity.CorruptIdentityMappingException;
import com.github.postyizhan.betterquesting.core.identity.IdentityAuditReport;
import com.github.postyizhan.betterquesting.core.identity.IdentityRecordRejection;
import com.github.postyizhan.betterquesting.core.identity.PersistentPlayerIdentityService;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/**
 * Binds one server session's identity service to that session's world storage.
 *
 * <p>The held {@link MiteWorldStorage} is world-lifetime bound, so this holder is explicitly not a
 * cross-world cache: {@link #bind(MinecraftServer)} resolves storage fresh per session,
 * {@link #unbind()} drops it on stop, and {@link #current(MinecraftServer)} refuses to serve a
 * service bound to a different server instance. An integrated server creates a new server and save
 * handler each time a world is entered, so without the identity check a second world would keep
 * writing the first world's directory (docs/handoff.md section 4.2).
 *
 * <p>{@code bind} must run after world load. Resolving before {@code worldServers} is populated
 * yields a permanently disabled storage with no retry path, which is why this is wired to the
 * server-started notification rather than to mod construction.
 *
 * <p>A corrupt mapping file makes binding fail. Serving identities with an empty mapping set would
 * let isolated legacy saves be re-claimed by derived identities.
 *
 * <p>Not covered by automated tests: it depends on {@code MinecraftServer}. The pure-JVM logic it
 * delegates to is tested directly.
 */
public final class ServerIdentityContext {
    private static MinecraftServer boundServer;
    private static PersistentPlayerIdentityService identities;

    private ServerIdentityContext() {
    }

    public static synchronized PersistentPlayerIdentityService bind(MinecraftServer server) throws IOException {
        Objects.requireNonNull(server, "server");
        unbind();

        MiteWorldStorage storage = MiteWorldStorage.resolve(server);
        if (!storage.isAvailable()) {
            throw new IOException("BetterQuesting identity storage is unavailable: "
                + storage.getDisabledReason().orElse("unknown reason"));
        }

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

        boundServer = server;
        identities = service;
        BetterQuestingMod.LOGGER.info("Loaded {} persisted BetterQuesting identity mappings",
            service.legacyMappingsSnapshot().size());
        return service;
    }

    /** Returns the service only when it belongs to {@code server}, so a stale binding is never used. */
    public static synchronized Optional<PersistentPlayerIdentityService> current(MinecraftServer server) {
        if (server == null || boundServer != server) {
            return Optional.empty();
        }
        return Optional.ofNullable(identities);
    }

    public static synchronized void unbind() {
        boundServer = null;
        identities = null;
    }

    private static void logRejections(IdentityAuditReport report) {
        for (IdentityRecordRejection rejection : report.rejections()) {
            BetterQuestingMod.LOGGER.warn("Rejected identity audit {}", rejection);
        }
    }
}
