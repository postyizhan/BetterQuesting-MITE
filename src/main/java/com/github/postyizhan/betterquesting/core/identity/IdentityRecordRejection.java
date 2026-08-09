package com.github.postyizhan.betterquesting.core.identity;

import java.util.Objects;

/** One rejected persisted line, kept so corruption is reportable instead of silently skipped. */
public final class IdentityRecordRejection {
    private final int lineNumber;
    private final String reason;
    private final String rawLine;

    public IdentityRecordRejection(int lineNumber, String reason, String rawLine) {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be 1-based");
        }
        this.lineNumber = lineNumber;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.rawLine = Objects.requireNonNull(rawLine, "rawLine");
    }

    public int lineNumber() {
        return lineNumber;
    }

    public String reason() {
        return reason;
    }

    public String rawLine() {
        return rawLine;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof IdentityRecordRejection other)) {
            return false;
        }
        return lineNumber == other.lineNumber && reason.equals(other.reason) && rawLine.equals(other.rawLine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineNumber, reason, rawLine);
    }

    @Override
    public String toString() {
        // The raw line is included because operators repairing a file by hand need the exact bytes,
        // and callers log the whole rejection with a single placeholder.
        return "line " + lineNumber + ": " + reason + " [raw: " + rawLine + ']';
    }
}
