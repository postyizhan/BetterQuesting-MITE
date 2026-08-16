package com.github.postyizhan.betterquesting.network.fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class BoundedFragmenter {
    private final FragmentAssemblyLimits limits;

    public BoundedFragmenter(FragmentAssemblyLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public FragmentAssemblyLimits limits() {
        return limits;
    }

    public FragmentAssemblyLimits getLimits() {
        return limits;
    }

    public List<QuestingFragment> split(long transferId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > limits.maxTransferBytes()) {
            throw new IllegalArgumentException("payload exceeds the transfer byte limit");
        }

        int maxFragmentBytes = limits.maxFragmentBytes();
        int fragmentCount = 1 + (payload.length - 1) / maxFragmentBytes;
        if (fragmentCount > limits.maxFragmentsPerTransfer()) {
            throw new IllegalArgumentException("payload exceeds the fragment count limit");
        }

        List<QuestingFragment> fragments = new ArrayList<QuestingFragment>(fragmentCount);
        for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
            int start = fragmentIndex * maxFragmentBytes;
            int end = start + Math.min(maxFragmentBytes, payload.length - start);
            fragments.add(new QuestingFragment(
                transferId,
                payload.length,
                fragmentIndex,
                fragmentCount,
                Arrays.copyOfRange(payload, start, end)
            ));
        }
        return List.copyOf(fragments);
    }
}
