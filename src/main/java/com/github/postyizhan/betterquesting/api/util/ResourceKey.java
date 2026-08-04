package com.github.postyizhan.betterquesting.api.util;

import java.util.Objects;

/**
 * Platform-neutral logical identifier. MITE's ResourceLocation performs resource existence verification,
 * so it cannot safely represent registry IDs or persisted property keys.
 */
public final class ResourceKey {
    private final String domain;
    private final String path;

    public ResourceKey(String domain, String path) {
        this.domain = domain == null || domain.isEmpty() ? "minecraft" : domain;
        this.path = Objects.requireNonNull(path, "path");
    }

    public static ResourceKey parse(String value) {
        String domain = "minecraft";
        String path = value;
        int separator = value.indexOf(':');
        if (separator >= 0) {
            path = value.substring(separator + 1, value.length());
            if (separator > 1) {
                domain = value.substring(0, separator);
            }
        }
        return new ResourceKey(domain.toLowerCase(), path);
    }

    public String getDomain() {
        return domain;
    }

    public String getPath() {
        return path;
    }

    public String getResourceDomain() {
        return domain;
    }

    public String getResourcePath() {
        return path;
    }

    @Override
    public String toString() {
        return domain + ":" + path;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ResourceKey)) {
            return false;
        }
        ResourceKey other = (ResourceKey) obj;
        return domain.equals(other.domain) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return 31 * domain.hashCode() + path.hashCode();
    }
}
