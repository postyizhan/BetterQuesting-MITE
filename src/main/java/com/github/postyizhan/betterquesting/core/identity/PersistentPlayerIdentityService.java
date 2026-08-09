package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityService;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds durable mappings and an append-only audit trail to {@link DeterministicPlayerIdentityService}.
 *
 * <p>Write order per mutation: apply in memory, append the audit entry, then rewrite the snapshot.
 * If the audit append fails the in-memory change is rolled back, so memory never diverges from a
 * missing audit entry. If the snapshot write fails the audit entry stays: the audit log records
 * decisions and is never rewritten, so it may legitimately contain an operation whose snapshot write
 * failed. The reverse order was rejected because a crash between the two steps would apply a change
 * with no audit record at all.
 *
 * <p>{@code IOException} from the storage layer is wrapped in {@link UncheckedIOException} because
 * {@link PlayerIdentityService} declares no checked exceptions. It is never swallowed.
 *
 * <p>Storage is injected. This class never calls {@code MiteWorldStorage.resolve()}: that object is
 * bound to one world's lifetime and resolving it too early yields a permanently disabled instance
 * (docs/handoff.md section 4.2).
 *
 * <p>Threading: every mutating method is synchronized on this instance, which serializes this
 * service's own writes to both files. That does not make {@code WorldStorage} concurrency-safe for
 * other writers; the storage layer still requires same-path serialization by its callers.
 */
public final class PersistentPlayerIdentityService implements PlayerIdentityService {
    private static final String DEFAULT_REMOVAL_DECISION =
        "administrator removed mapping without a recorded reason";

    private final DeterministicPlayerIdentityService delegate;
    private final LegacyMappingStore mappings;
    private final IdentityAuditLog auditLog;
    /** Non-null once a snapshot write failed, leaving memory ahead of disk. */
    private String poisonedReason;

    public PersistentPlayerIdentityService(WorldStorage storage) {
        this(storage, Clock.systemUTC());
    }

    public PersistentPlayerIdentityService(WorldStorage storage, Clock clock) {
        this(new DeterministicPlayerIdentityService(),
            new LegacyMappingStore(Objects.requireNonNull(storage, "storage")),
            new IdentityAuditLog(storage, clock));
    }

    public PersistentPlayerIdentityService(DeterministicPlayerIdentityService delegate,
                                           LegacyMappingStore mappings, IdentityAuditLog auditLog) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    /**
     * Restores persisted mappings and resumes audit sequence numbering. Call once after world load
     * and before serving any identity.
     *
     * @return the audit read report, whose rejections the caller must surface
     * @throws CorruptIdentityMappingException if the mapping file exists but is not fully valid;
     *     callers must not fall back to an empty mapping set, because that would let isolated legacy
     *     saves be re-claimed
     */
    public synchronized IdentityAuditReport loadFromStorage() throws IOException {
        Optional<Map<UUID, PlayerIdentityResolution>> stored = mappings.load();
        delegate.restoreMappings(stored.orElse(Map.of()));
        // Memory now matches disk again, so a previous snapshot-write failure is cleared.
        poisonedReason = null;
        return auditLog.initializeSequenceFromStorage();
    }

    public IdentityAuditLog auditLog() {
        return auditLog;
    }

    /** True once a snapshot write failed; mutations are refused until a successful reload. */
    public synchronized boolean isPoisoned() {
        return poisonedReason != null;
    }

    public synchronized Optional<String> poisonedReason() {
        return Optional.ofNullable(poisonedReason);
    }

    private void requireNotPoisoned() {
        if (poisonedReason != null) {
            throw new IllegalStateException("BetterQuesting identity service is unusable: " + poisonedReason);
        }
    }

    @Override
    public PlayerIdentityResolution resolveUsername(String username) {
        return delegate.resolveUsername(username);
    }

    @Override
    public synchronized PlayerIdentityResolution resolveLegacy(UUID legacyUuid) {
        return delegate.resolveLegacy(legacyUuid);
    }

    @Override
    public synchronized PlayerIdentityResolution mapLegacy(UUID legacyUuid, String username, String decision) {
        requireNotPoisoned();
        Map<UUID, PlayerIdentityResolution> before = delegate.legacyMappingsSnapshot();
        return persist(IdentityAuditOperation.MAP, delegate.mapLegacy(legacyUuid, username, decision), before);
    }

    @Override
    public synchronized PlayerIdentityResolution mergeLegacy(UUID legacyUuid, String username, String decision) {
        requireNotPoisoned();
        Map<UUID, PlayerIdentityResolution> before = delegate.legacyMappingsSnapshot();
        return persist(IdentityAuditOperation.MERGE, delegate.mergeLegacy(legacyUuid, username, decision), before);
    }

    @Override
    public synchronized PlayerIdentityResolution replaceLegacyMapping(UUID legacyUuid, String username,
                                                                       String decision) {
        requireNotPoisoned();
        Map<UUID, PlayerIdentityResolution> before = delegate.legacyMappingsSnapshot();
        return persist(IdentityAuditOperation.REPLACE,
            delegate.replaceLegacyMapping(legacyUuid, username, decision), before);
    }

    /**
     * Removes a mapping without a caller-supplied reason. The audit entry records a fixed placeholder
     * decision, because {@link PlayerIdentityService#removeLegacyMapping} carries no reason argument.
     * Prefer {@link #removeLegacyMapping(UUID, String)} so the audit captures the real decision.
     */
    @Override
    public synchronized Optional<PlayerIdentityResolution> removeLegacyMapping(UUID legacyUuid) {
        return removeLegacyMapping(legacyUuid, DEFAULT_REMOVAL_DECISION);
    }

    /**
     * Removes a mapping and audits the administrator's stated reason. The audit entry keeps the
     * removed mapping's legacy UUID and identity so the trail shows exactly what was unmapped; the
     * decision field holds the removal reason rather than the original mapping's reason.
     */
    public synchronized Optional<PlayerIdentityResolution> removeLegacyMapping(UUID legacyUuid, String decision) {
        Objects.requireNonNull(legacyUuid, "legacyUuid");
        Objects.requireNonNull(decision, "decision");
        requireNotPoisoned();
        Map<UUID, PlayerIdentityResolution> before = delegate.legacyMappingsSnapshot();
        Optional<PlayerIdentityResolution> removed = delegate.removeLegacyMapping(legacyUuid);
        if (removed.isEmpty()) {
            // Nothing changed, so neither an audit entry nor a snapshot rewrite is warranted.
            return removed;
        }
        PlayerIdentityResolution removalRecord = PlayerIdentityResolution.mapped(
            legacyUuid, removed.orElseThrow().identity().orElseThrow(), decision);
        persist(IdentityAuditOperation.REMOVE, removalRecord, before);
        return removed;
    }

    @Override
    public synchronized Map<UUID, PlayerIdentityResolution> legacyMappingsSnapshot() {
        return delegate.legacyMappingsSnapshot();
    }

    /**
     * @param before the mapping state captured before the delegate mutation, used to roll back if the
     *     audit entry cannot be written
     */
    private PlayerIdentityResolution persist(IdentityAuditOperation operation,
                                             PlayerIdentityResolution resolution,
                                             Map<UUID, PlayerIdentityResolution> before) {
        Map<UUID, PlayerIdentityResolution> applied = delegate.legacyMappingsSnapshot();
        try {
            auditLog.append(operation, resolution);
        } catch (IOException | RuntimeException failure) {
            // Revert so a running server never serves a mapping that has no audit record.
            try {
                delegate.restoreMappings(before);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof IOException audit) {
                throw new UncheckedIOException("Failed to append identity audit entry for " + operation, audit);
            }
            throw (RuntimeException) failure;
        }
        try {
            mappings.save(applied);
        } catch (IOException failure) {
            // Memory is now ahead of disk. Refuse further mutations rather than stacking more
            // unpersisted changes on top of a known-diverged state.
            poisonedReason = "mapping snapshot write failed during " + operation
                + "; in-memory mappings are ahead of disk";
            throw new UncheckedIOException(
                "Identity audit entry was recorded but the mapping snapshot could not be saved for "
                    + operation, failure);
        }
        return resolution;
    }
}
