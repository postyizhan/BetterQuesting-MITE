package com.github.postyizhan.betterquesting.network.sync;

import java.util.Objects;

/** Closed logical payload set carried by login bulk fragment transfers. */
public record LoginBulkPayload(LoginLifeSnapshot life, LoginNameSnapshot name) {
    public LoginBulkPayload {
        if ((life == null) == (name == null)) {
            throw new IllegalArgumentException("exactly one login bulk payload variant is required");
        }
    }

    public static LoginBulkPayload life(LoginLifeSnapshot snapshot) {
        return new LoginBulkPayload(Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    public static LoginBulkPayload name(LoginNameSnapshot snapshot) {
        return new LoginBulkPayload(null, Objects.requireNonNull(snapshot, "snapshot"));
    }

    public String id() {
        return life != null ? LoginLifeSnapshot.FORMAT_ID : LoginNameSnapshot.FORMAT_ID;
    }

    public int version() {
        return life != null ? LoginLifeSnapshot.FORMAT_VERSION : LoginNameSnapshot.FORMAT_VERSION;
    }
}
