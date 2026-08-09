package com.github.postyizhan.betterquesting.core.storage.json;

import java.io.IOException;

/**
 * Signals that a file's bytes are not a valid JSON object.
 *
 * <p>An {@link IOException} subtype so it flows through the storage boundary unchanged, while
 * remaining distinguishable from a genuine IO fault: only this type warrants quarantining the file
 * as {@code malformed_<name>.json}, whereas a disk error must not move the original.
 */
public class MalformedJsonDocumentException extends IOException {
    private static final long serialVersionUID = 1L;

    public MalformedJsonDocumentException(String message) {
        super(message);
    }

    public MalformedJsonDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
