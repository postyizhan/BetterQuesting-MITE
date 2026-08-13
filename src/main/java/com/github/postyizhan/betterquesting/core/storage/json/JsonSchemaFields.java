package com.github.postyizhan.betterquesting.core.storage.json;

import com.github.postyizhan.betterquesting.api.util.NbtCompat;
import net.minecraft.NBTTagCompound;

/**
 * The three schema-stamp fields written at the root of a BetterQuesting document.
 *
 * <p>Upstream behaviour, verified against BetterQuesting-master:
 * <ul>
 *   <li>{@code format} — constant {@code BetterQuesting.java:57} {@code FORMAT = "3.1.0"}. Written
 *       at SaveLoadHandler.java:307, QuestCommandDefaults.java:207 and
 *       QuestCommandDefaults.java:342. <b>Never read anywhere in upstream</b>: a repository-wide
 *       search for {@code getString("format")}, {@code has("format")} and {@code hasKey("format")}
 *       finds no call site, so upstream has no format-version branch at all. It is a
 *       forward-compatibility marker for external tooling only.</li>
 *   <li>{@code build} — the running mod version, written at SaveLoadHandler.java:308-312 from
 *       {@code Loader.instance().activeModContainer().getVersion()} and read back at
 *       SaveLoadHandler.java:196-197. This is upstream's only version branch: when the stored value
 *       differs from the current one by {@code equalsIgnoreCase} (SaveLoadHandler.java:202) it
 *       copies five databases into {@code backup/<storedVersion>/} before loading
 *       (SaveLoadHandler.java:216-232), substituting {@code "pre-251"} for an empty stored value
 *       (SaveLoadHandler.java:206-208). Note it is written only by {@code saveConfig}, so the
 *       default-pack files carry {@code format} without {@code build}.</li>
 *   <li>{@code mitePortFormat} — added by this port. It advances only when the port changes a
 *       document's own layout, independently of the upstream {@code format} value it stays
 *       compatible with, so a reader can tell a port-written file from an upstream-written one
 *       without inferring it from {@code build}.</li>
 * </ul>
 *
 * <p>All three are string tags, so they survive the {@code format=true} dialect as
 * {@code "format:8"}, {@code "build:8"} and {@code "mitePortFormat:8"}.
 */
public final class JsonSchemaFields {
    public static final String FORMAT_KEY = "format";
    public static final String BUILD_KEY = "build";
    public static final String MITE_PORT_FORMAT_KEY = "mitePortFormat";

    /** Upstream {@code BetterQuesting.FORMAT} (BetterQuesting.java:57), preserved verbatim. */
    public static final String UPSTREAM_FORMAT = "3.1.0";

    /**
     * Port schema revision. {@code "1"} is the first revision that carries this field; a file
     * without it predates the port or was written by upstream.
     */
    public static final String MITE_PORT_FORMAT = "1";

    /** Upstream's substitute for an absent or empty stored build (SaveLoadHandler.java:206-208). */
    public static final String LEGACY_BUILD = "pre-251";

    private JsonSchemaFields() {
    }

    /**
     * Stamps all three fields onto a document root.
     *
     * @param build the running mod version, which upstream sources from its mod container
     */
    public static void stamp(NBTTagCompound root, String build) {
        root.setString(FORMAT_KEY, UPSTREAM_FORMAT);
        root.setString(BUILD_KEY, build == null ? "" : build);
        root.setString(MITE_PORT_FORMAT_KEY, MITE_PORT_FORMAT);
    }

    /** Returns the stored build, or {@link #LEGACY_BUILD} when absent or empty, per upstream. */
    public static String readBuild(NBTTagCompound root) {
        String stored = root == null ? "" : root.getString(BUILD_KEY);
        return stored == null || stored.isEmpty() ? LEGACY_BUILD : stored;
    }

    /**
     * Returns true when the stored build differs from {@code currentBuild}, which is upstream's
     * trigger for a version-upgrade backup. Comparison is case-insensitive to match
     * SaveLoadHandler.java:202.
     */
    public static boolean isBuildUpgrade(NBTTagCompound root, String currentBuild) {
        String stored = root == null ? "" : root.getString(BUILD_KEY);
        return !(currentBuild == null ? "" : currentBuild).equalsIgnoreCase(stored == null ? "" : stored);
    }

    /** Returns the stored port schema revision, or an empty string for upstream-written files. */
    public static String readMitePortFormat(NBTTagCompound root) {
        String stored = root == null ? "" : root.getString(MITE_PORT_FORMAT_KEY);
        return stored == null ? "" : stored;
    }

    /**
     * Returns true for upstream documents (no port marker) and for this port's exact revision.
     * Present markers with another NBT type or value are unsupported and must be quarantined before
     * a later save could rewrite them as the current revision.
     */
    public static boolean isCompatibleMitePortFormat(NBTTagCompound root) {
        if (root == null || !root.hasKey(MITE_PORT_FORMAT_KEY)) {
            return true;
        }
        return NbtCompat.getTagId(root, MITE_PORT_FORMAT_KEY) == 8
            && MITE_PORT_FORMAT.equals(root.getString(MITE_PORT_FORMAT_KEY));
    }
}
