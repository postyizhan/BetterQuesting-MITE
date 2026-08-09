package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentitySource;
import java.util.UUID;

/**
 * Strict field parsers shared by the audit and mapping record layouts.
 *
 * <p>Every parser demands the canonical textual form. That keeps the codec's round-trip check
 * meaningful and prevents two different byte sequences from denoting the same record.
 */
final class IdentityRecordFields {
    private IdentityRecordFields() {
    }

    static long canonicalLong(String value, String fieldName) throws IdentityRecordFormatException {
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IdentityRecordFormatException(fieldName + " is not a decimal long: " + value);
        }
        if (!Long.toString(parsed).equals(value)) {
            throw new IdentityRecordFormatException(fieldName + " is not canonical decimal: " + value);
        }
        return parsed;
    }

    static UUID canonicalUuid(String value, String fieldName) throws IdentityRecordFormatException {
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IdentityRecordFormatException(fieldName + " is not a UUID: " + value);
        }
        if (!parsed.toString().equals(value)) {
            throw new IdentityRecordFormatException(fieldName + " is not a canonical lowercase UUID: " + value);
        }
        return parsed;
    }

    static IdentityAuditOperation operation(String value) throws IdentityRecordFormatException {
        for (IdentityAuditOperation candidate : IdentityAuditOperation.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new IdentityRecordFormatException("unknown audit operation: " + value);
    }

    /**
     * Accepts only {@code ADMIN_EXPLICIT_LEGACY_MAPPING}. Persisted mappings and audit entries are
     * produced exclusively by administrator operations, so any other source value indicates a
     * tampered or foreign record.
     */
    static PlayerIdentitySource adminMappingSource(String value) throws IdentityRecordFormatException {
        if (!PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING.name().equals(value)) {
            throw new IdentityRecordFormatException("unsupported identity source for a persisted mapping: " + value);
        }
        return PlayerIdentitySource.ADMIN_EXPLICIT_LEGACY_MAPPING;
    }

    /**
     * Builds an identity and requires its UUID to be the value derived from its own username.
     *
     * <p>The derivation check runs here, at the parse boundary, so a forged record becomes a
     * reportable rejection rather than an exception escaping from the later in-memory restore.
     */
    static PlayerIdentity identity(UUID identityUuid, String normalizedUsername)
        throws IdentityRecordFormatException {
        try {
            PlayerIdentity identity = new PlayerIdentity(identityUuid, normalizedUsername);
            DeterministicPlayerIdentityService.requireDerivedIdentity(identity);
            return identity;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IdentityRecordFormatException("invalid identity fields: " + invalid.getMessage());
        }
    }

    static PlayerIdentityResolution mappedResolution(UUID legacyUuid, PlayerIdentity identity, String decision)
        throws IdentityRecordFormatException {
        try {
            return PlayerIdentityResolution.mapped(legacyUuid, identity, decision);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IdentityRecordFormatException("invalid resolution fields: " + invalid.getMessage());
        }
    }
}
