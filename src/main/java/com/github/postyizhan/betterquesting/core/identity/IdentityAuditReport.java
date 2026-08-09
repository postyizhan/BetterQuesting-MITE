package com.github.postyizhan.betterquesting.core.identity;

import java.util.List;
import java.util.Objects;

/**
 * Result of reading the audit log: accepted records plus every rejected line.
 *
 * <p>Rejections are returned rather than skipped so a caller can surface crash fragments, truncated
 * tails and tampered checksums. A caller that ignores {@link #rejections()} silently loses the
 * evidence that the log is incomplete.
 */
public final class IdentityAuditReport {
    private final List<IdentityAuditRecord> records;
    private final List<IdentityRecordRejection> rejections;

    public IdentityAuditReport(List<IdentityAuditRecord> records, List<IdentityRecordRejection> rejections) {
        this.records = List.copyOf(Objects.requireNonNull(records, "records"));
        this.rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
    }

    public List<IdentityAuditRecord> records() {
        return records;
    }

    public List<IdentityRecordRejection> rejections() {
        return rejections;
    }

    public boolean hasRejections() {
        return !rejections.isEmpty();
    }

    @Override
    public String toString() {
        return "IdentityAuditReport{records=" + records.size() + ", rejections=" + rejections + '}';
    }
}
