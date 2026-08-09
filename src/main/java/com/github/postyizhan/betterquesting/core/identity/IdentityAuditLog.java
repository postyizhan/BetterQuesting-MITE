package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only audit log for administrator identity mapping decisions.
 *
 * <p>Storage is injected. This class never resolves a {@code WorldStorage} itself, because the
 * platform implementation is bound to one world's lifetime: an integrated server builds a new
 * server and save handler per world, and resolving before {@code worldServers} is populated
 * produces a permanently disabled instance (docs/handoff.md section 4.2).
 *
 * <p>Threading: not internally synchronized against other writers of the same file. Call on the
 * server main thread, or serialize externally. {@code WorldStorage.appendLine} carries the same
 * same-path constraint for its last-byte framing guard and truncate rollback.
 *
 * <p>Sequence numbers come from {@link #initializeSequenceFromStorage()}, which reads the existing
 * log once so numbering continues across restarts. Without that call the first append starts at 1
 * and would collide with existing records.
 */
public final class IdentityAuditLog {
    public static final String AUDIT_PATH = "identity/IdentityAudit.log";

    private final WorldStorage storage;
    private final Clock clock;
    private long lastSequence;

    public IdentityAuditLog(WorldStorage storage) {
        this(storage, Clock.systemUTC());
    }

    public IdentityAuditLog(WorldStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Reads the existing log and continues numbering past the highest sequence present in the file,
     * counting rejected lines as well as accepted records.
     *
     * <p>A rejected line's content is refused but its sequence must still be consumed. The dangerous
     * fragment is not a truncated record, which always fails the field-count check, but a complete
     * checksum-valid record that lost only its terminating LF, which is exactly the case
     * {@code appendLine}'s framing guard exists for. If such a line were skipped, the next append
     * would reuse its sequence; the LF that {@code appendLine} then prepends would terminate the
     * fragment into a valid line, and from that point every {@link #read()} would accept the
     * crash-era line and permanently reject the newer legitimate record as non-increasing. The
     * snapshot would hold the mapping while the audit log never showed it. One power loss is enough
     * to trigger this, with no tampering involved.
     *
     * <p>A rejected line whose sequence field cannot be parsed contributes nothing, which is safe: a
     * fragment that cannot yield a sequence can never later become an accepted record.
     */
    public IdentityAuditReport initializeSequenceFromStorage() throws IOException {
        IdentityAuditReport report = read();
        long highest = lastSequence;
        for (IdentityAuditRecord record : report.records()) {
            highest = Math.max(highest, record.sequence());
        }
        for (IdentityRecordRejection rejection : report.rejections()) {
            highest = Math.max(highest, lenientSequence(rejection.rawLine()));
        }
        lastSequence = highest;
        return report;
    }

    /**
     * Extracts the sequence field from a line that failed validation, without requiring a valid
     * checksum, magic or field count. Returns 0 when no plausible sequence can be read.
     */
    private static long lenientSequence(String rawLine) {
        String[] fields = rawLine.split("\\|", -1);
        if (fields.length < 2) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(fields[1]));
        } catch (NumberFormatException notASequence) {
            return 0;
        }
    }

    public IdentityAuditRecord append(IdentityAuditOperation operation, PlayerIdentityResolution resolution)
        throws IOException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(resolution, "resolution");
        if (lastSequence == Long.MAX_VALUE) {
            // The counter can be driven by a file-supplied value, so refuse rather than wrap and
            // reuse a sequence that may already exist in the log.
            throw new IOException("Identity audit sequence is exhausted; archive " + AUDIT_PATH);
        }
        IdentityAuditRecord record =
            new IdentityAuditRecord(lastSequence + 1, clock.millis(), operation, resolution);
        storage.appendLine(AUDIT_PATH, record.encode());
        lastSequence = record.sequence();
        return record;
    }

    /**
     * Parses every framed line. Invalid lines, out-of-order sequences and an unterminated trailing
     * fragment become rejections instead of being dropped.
     */
    public IdentityAuditReport read() throws IOException {
        byte[] bytes = storage.read(AUDIT_PATH, input -> input.readAllBytes()).orElse(null);
        if (bytes == null) {
            return new IdentityAuditReport(List.of(), List.of());
        }

        List<IdentityAuditRecord> records = new ArrayList<>();
        List<IdentityRecordRejection> rejections = new ArrayList<>();
        List<String> lines = FramedLines.completeLines(bytes);
        long previousSequence = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            final IdentityAuditRecord record;
            try {
                record = IdentityAuditRecord.decode(line);
            } catch (IdentityRecordFormatException invalid) {
                rejections.add(new IdentityRecordRejection(lineNumber, invalid.getMessage(), line));
                continue;
            }
            if (record.sequence() <= previousSequence) {
                rejections.add(new IdentityRecordRejection(lineNumber,
                    "sequence " + record.sequence() + " does not increase past " + previousSequence, line));
                continue;
            }
            previousSequence = record.sequence();
            records.add(record);
        }
        if (FramedLines.hasUnterminatedTail(bytes)) {
            rejections.add(new IdentityRecordRejection(lines.size() + 1,
                "trailing bytes are not terminated by LF", FramedLines.unterminatedTail(bytes)));
        }
        return new IdentityAuditReport(records, rejections);
    }
}
