package com.github.postyizhan.betterquesting.core.identity;

import java.io.IOException;

/**
 * Signals that one persisted identity line is not a valid record. Callers decide whether a single
 * bad line is reportable (audit log) or fatal (mapping snapshot); no caller may treat it as absent
 * data.
 */
public final class IdentityRecordFormatException extends IOException {
    public IdentityRecordFormatException(String message) {
        super(message);
    }
}
