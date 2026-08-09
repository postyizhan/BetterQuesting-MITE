package com.github.postyizhan.betterquesting.core.storage.json;

/**
 * Sink for recoverable codec anomalies.
 *
 * <p>Upstream {@code NBTConverter} logged these through {@code QuestingAPI.getLogger()}. This port
 * keeps {@code core} and {@code api} free of any loader or logging dependency, so the codec reports
 * anomalies here and platform wiring supplies a logging implementation. Tests can assert on the
 * collected messages, which log calls did not allow.
 */
@FunctionalInterface
public interface NbtJsonDiagnostics {
    /** Discards every message; the default for callers that do not observe anomalies. */
    NbtJsonDiagnostics IGNORE = message -> {
    };

    void warn(String message);
}
