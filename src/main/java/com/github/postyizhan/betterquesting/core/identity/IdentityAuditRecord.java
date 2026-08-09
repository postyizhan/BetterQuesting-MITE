package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentitySource;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit entry for an administrator mapping operation.
 *
 * <p>The identity source and administrator decision are carried by the embedded
 * {@link PlayerIdentityResolution}, reusing its existing four-field value semantics rather than
 * duplicating those fields (plan.md stage 3 item 7).
 *
 * <p>On-disk layout, 8 payload fields between the magic and the checksum:
 *
 * <ol>
 *   <li>{@code sequence} — decimal long, minimum 1, canonical (no sign, no leading zeros)</li>
 *   <li>{@code epochMilli} — decimal long, minimum 0, canonical</li>
 *   <li>{@code operation} — an {@link IdentityAuditOperation} name</li>
 *   <li>{@code legacyUuid} — canonical lowercase UUID text</li>
 *   <li>{@code identityUuid} — canonical lowercase UUID text</li>
 *   <li>{@code normalizedUsername} — must equal the identity's normalized username</li>
 *   <li>{@code source} — a {@link PlayerIdentitySource} name; only
 *       {@code ADMIN_EXPLICIT_LEGACY_MAPPING} is accepted, because that is the only source the four
 *       administrator operations can produce. A record claiming a derived source is a forgery
 *       attempt or a format change, and both must be rejected rather than trusted.</li>
 *   <li>{@code decision} — administrator decision text, escaped, non-blank</li>
 * </ol>
 */
public final class IdentityAuditRecord {
    static final String MAGIC = "BQIDAUDIT1";
    static final int PAYLOAD_FIELDS = 8;

    private final long sequence;
    private final long epochMilli;
    private final IdentityAuditOperation operation;
    private final PlayerIdentityResolution resolution;

    public IdentityAuditRecord(long sequence, long epochMilli, IdentityAuditOperation operation,
                               PlayerIdentityResolution resolution) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if (epochMilli < 0) {
            throw new IllegalArgumentException("epochMilli must not be negative");
        }
        this.sequence = sequence;
        this.epochMilli = epochMilli;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        if (resolution.legacyUuid().isEmpty() || resolution.identity().isEmpty()) {
            throw new IllegalArgumentException("audit record requires both a legacy UUID and an identity");
        }
        if (resolution.source() != PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING) {
            throw new IllegalArgumentException("audit record requires an explicit administrator mapping source");
        }
    }

    public long sequence() {
        return sequence;
    }

    public long epochMilli() {
        return epochMilli;
    }

    public IdentityAuditOperation operation() {
        return operation;
    }

    public PlayerIdentityResolution resolution() {
        return resolution;
    }

    String encode() {
        PlayerIdentity identity = resolution.identity().orElseThrow();
        return IdentityRecordCodec.encode(MAGIC, List.of(
            Long.toString(sequence),
            Long.toString(epochMilli),
            operation.name(),
            resolution.legacyUuid().orElseThrow().toString(),
            identity.id().toString(),
            identity.normalizedUsername(),
            resolution.source().name(),
            resolution.decision()));
    }

    static IdentityAuditRecord decode(String line) throws IdentityRecordFormatException {
        List<String> payload = IdentityRecordCodec.decode(MAGIC, PAYLOAD_FIELDS, line);
        long sequence = IdentityRecordFields.canonicalLong(payload.get(0), "sequence");
        long epochMilli = IdentityRecordFields.canonicalLong(payload.get(1), "epochMilli");
        IdentityAuditOperation operation = IdentityRecordFields.operation(payload.get(2));
        UUID legacyUuid = IdentityRecordFields.canonicalUuid(payload.get(3), "legacyUuid");
        UUID identityUuid = IdentityRecordFields.canonicalUuid(payload.get(4), "identityUuid");
        String normalizedUsername = payload.get(5);
        PlayerIdentitySource source = IdentityRecordFields.adminMappingSource(payload.get(6));
        String decision = payload.get(7);

        PlayerIdentity identity = IdentityRecordFields.identity(identityUuid, normalizedUsername);
        PlayerIdentityResolution resolution =
            IdentityRecordFields.mappedResolution(legacyUuid, identity, decision);
        if (resolution.source() != source) {
            throw new IdentityRecordFormatException("source field does not match the reconstructed resolution");
        }
        try {
            return new IdentityAuditRecord(sequence, epochMilli, operation, resolution);
        } catch (IllegalArgumentException invalid) {
            throw new IdentityRecordFormatException("invalid audit record: " + invalid.getMessage());
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof IdentityAuditRecord other)) {
            return false;
        }
        return sequence == other.sequence && epochMilli == other.epochMilli
            && operation == other.operation && resolution.equals(other.resolution);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, epochMilli, operation, resolution);
    }

    @Override
    public String toString() {
        return "IdentityAuditRecord{sequence=" + sequence + ", epochMilli=" + epochMilli
            + ", operation=" + operation + ", resolution=" + resolution + '}';
    }
}
