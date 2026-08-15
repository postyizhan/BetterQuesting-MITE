package com.github.postyizhan.betterquesting.network.operation;

public record OperationDeduplicationLimits(int maxEntries, long expiryNanos) {
    public OperationDeduplicationLimits {
        if (maxEntries <= 0 || expiryNanos <= 0L) {
            throw new IllegalArgumentException("operation deduplication limits must be positive");
        }
    }
}
