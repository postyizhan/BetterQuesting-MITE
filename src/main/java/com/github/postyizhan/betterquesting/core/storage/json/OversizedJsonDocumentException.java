package com.github.postyizhan.betterquesting.core.storage.json;

import java.io.IOException;

/** Signals that a JSON document exceeded the bounded safety read. */
public final class OversizedJsonDocumentException extends IOException {
    private static final long serialVersionUID = 1L;

    public OversizedJsonDocumentException(String message) {
        super(message);
    }
}
