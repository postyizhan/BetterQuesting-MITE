package com.github.postyizhan.betterquesting.network.sync;

import java.util.Objects;

/** Closed logical payload set carried by login bulk fragment transfers. */
public record LoginBulkPayload(LoginLifeSnapshot life) {
    public LoginBulkPayload {
        Objects.requireNonNull(life, "life");
    }

    public static LoginBulkPayload life(LoginLifeSnapshot snapshot) {
        return new LoginBulkPayload(snapshot);
    }

    public String id() {
        return LoginLifeSnapshot.FORMAT_ID;
    }

    public int version() {
        return LoginLifeSnapshot.FORMAT_VERSION;
    }
}
