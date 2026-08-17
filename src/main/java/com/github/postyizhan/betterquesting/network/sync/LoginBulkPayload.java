package com.github.postyizhan.betterquesting.network.sync;

import java.util.Objects;

/** Closed logical payload set carried by login bulk fragment transfers. */
public record LoginBulkPayload(
    LoginChapterSnapshot chapter,
    LoginLifeSnapshot life,
    LoginNameSnapshot name
) {
    public LoginBulkPayload {
        int variants = (chapter == null ? 0 : 1) + (life == null ? 0 : 1) + (name == null ? 0 : 1);
        if (variants != 1) {
            throw new IllegalArgumentException("exactly one login bulk payload variant is required");
        }
    }

    public LoginBulkPayload(LoginLifeSnapshot life, LoginNameSnapshot name) {
        this(null, life, name);
    }

    public static LoginBulkPayload chapter(LoginChapterSnapshot snapshot) {
        return new LoginBulkPayload(Objects.requireNonNull(snapshot, "snapshot"), null, null);
    }

    public static LoginBulkPayload life(LoginLifeSnapshot snapshot) {
        return new LoginBulkPayload(null, Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    public static LoginBulkPayload name(LoginNameSnapshot snapshot) {
        return new LoginBulkPayload(null, null, Objects.requireNonNull(snapshot, "snapshot"));
    }

    public String id() {
        return chapter != null ? LoginChapterSnapshot.FORMAT_ID
            : life != null ? LoginLifeSnapshot.FORMAT_ID : LoginNameSnapshot.FORMAT_ID;
    }

    public int version() {
        return chapter != null ? LoginChapterSnapshot.FORMAT_VERSION
            : life != null ? LoginLifeSnapshot.FORMAT_VERSION : LoginNameSnapshot.FORMAT_VERSION;
    }
}
