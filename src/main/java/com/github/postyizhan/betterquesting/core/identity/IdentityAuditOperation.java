package com.github.postyizhan.betterquesting.core.identity;

/**
 * The administrator mapping operations that produce an audit entry. Names are part of the on-disk
 * record format; renaming a constant invalidates existing audit files.
 */
public enum IdentityAuditOperation {
    MAP,
    MERGE,
    REPLACE,
    REMOVE
}
