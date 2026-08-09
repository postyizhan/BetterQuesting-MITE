package com.github.postyizhan.betterquesting.core.storage.json;

import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.NBTTagCompound;

/**
 * Reads and writes BetterQuesting JSON documents over a {@link WorldStorage}.
 *
 * <p>Two upstream behaviours are reproduced here:
 * <ul>
 *   <li>A parse failure quarantines the file as {@code malformed_<name>.json} beside the original
 *       and reports an empty document, mirroring JsonHelper.java:185-195. See
 *       {@link #quarantineNameFor} for the literal naming rule.</li>
 *   <li>A write is validated by re-parsing the finished temporary file before it replaces the
 *       target, mirroring JsonHelper.java:247-255. A document that cannot be read back never
 *       replaces a good one.</li>
 * </ul>
 *
 * <p>Inherits {@link WorldStorage}'s same-path serialization requirement: call from the server main
 * thread or otherwise prevent concurrent access to one relative path.
 */
public final class JsonDocumentStore {
    private static final String QUARANTINE_PREFIX = "malformed_";
    private static final String QUARANTINE_SUFFIX = ".json";

    private final WorldStorage storage;
    private final NbtJsonCodec codec;
    private final NbtJsonDiagnostics diagnostics;

    public JsonDocumentStore(WorldStorage storage) {
        this(storage, new NbtJsonCodec(), NbtJsonDiagnostics.IGNORE);
    }

    public JsonDocumentStore(WorldStorage storage, NbtJsonCodec codec, NbtJsonDiagnostics diagnostics) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Loads one document as NBT.
     *
     * <p>A missing file yields {@link Outcome#ABSENT}. Unreadable content yields
     * {@link Outcome#QUARANTINED} with an empty root, after copying the original aside; the original
     * is deliberately left in place so an administrator can inspect it, which means a repeated load
     * re-copies it. Genuine IO faults propagate rather than being reported as malformed, so a
     * transient disk error cannot be mistaken for data loss.
     */
    public LoadResult load(String relativePath, boolean format) throws IOException {
        Optional<NBTTagCompound> loaded;
        try {
            loaded = storage.read(relativePath,
                input -> codec.toNbt(JsonDocuments.parseObject(input), new NBTTagCompound(), format));
        } catch (MalformedJsonDocumentException malformed) {
            diagnostics.warn("An error occurred while loading JSON from file " + relativePath
                + ": " + malformed);
            String quarantinePath = quarantine(relativePath);
            return new LoadResult(Outcome.QUARANTINED, new NBTTagCompound(), quarantinePath);
        }

        return loaded
            .map(root -> new LoadResult(Outcome.LOADED, root, null))
            .orElseGet(() -> new LoadResult(Outcome.ABSENT, new NBTTagCompound(), null));
    }

    /**
     * Writes one document, validating that it parses back before the replacement is committed.
     *
     * <p>The payload is streamed straight from NBT, so a large database is never materialized as a
     * {@code JsonObject} tree first.
     */
    public void save(String relativePath, NBTTagCompound root, boolean format) throws IOException {
        Objects.requireNonNull(root, "root");
        storage.writeAtomically(relativePath,
            output -> writeDocument(output, root, format),
            JsonDocuments::parseObject);
    }

    private void writeDocument(OutputStream output, NBTTagCompound root, boolean format) throws IOException {
        // JsonWriter is not closed here: closing it would close the caller's stream, which
        // AtomicFileStorage owns and still needs to sync. Flushing pushes the buffered characters
        // through, which is the contract OutputWriter states.
        com.google.gson.stream.JsonWriter json = JsonDocuments.writer(output);
        codec.write(root, json, format);
        json.flush();
    }

    private String quarantine(String relativePath) throws IOException {
        String quarantinePath = quarantineNameFor(relativePath);
        byte[] original = storage.read(relativePath, JsonDocumentStore::readAllBytes)
            .orElse(null);
        if (original == null) {
            // The file vanished between the failed parse and the copy; nothing to preserve.
            return null;
        }
        storage.writeAtomically(quarantinePath, output -> output.write(original));
        diagnostics.warn("Created backup at: " + quarantinePath);
        return quarantinePath;
    }

    /**
     * Builds the quarantine path for a document.
     *
     * <p>Reproduces upstream JsonHelper.java:189 literally: the prefix and the {@code .json} suffix
     * are both applied to the <em>whole</em> original file name, so {@code QuestDatabase.json}
     * becomes {@code malformed_QuestDatabase.json.json}. The doubled extension is upstream's, kept
     * so a world migrated from 1.7.10 has one naming rule rather than two.
     *
     * <p>The result always contains {@code malformed_}, which
     * {@code WorldDataStorage.list} excludes, so a quarantined file is never re-enumerated as
     * player progress. That exclusion lives solely in {@code list}; nothing here filters again.
     */
    public static String quarantineNameFor(String relativePath) {
        int lastSeparator = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        String directory = lastSeparator < 0 ? "" : relativePath.substring(0, lastSeparator + 1);
        String name = relativePath.substring(lastSeparator + 1);
        return directory + QUARANTINE_PREFIX + name + QUARANTINE_SUFFIX;
    }

    private static byte[] readAllBytes(java.io.InputStream input) throws IOException {
        return input.readAllBytes();
    }

    /** What {@link #load} found on disk. */
    public enum Outcome {
        ABSENT,
        LOADED,
        QUARANTINED
    }

    /** Load outcome plus the resulting root; the root is empty unless {@link Outcome#LOADED}. */
    public static final class LoadResult {
        private final Outcome outcome;
        private final NBTTagCompound root;
        private final String quarantinePath;

        LoadResult(Outcome outcome, NBTTagCompound root, String quarantinePath) {
            this.outcome = outcome;
            this.root = root;
            this.quarantinePath = quarantinePath;
        }

        public Outcome outcome() {
            return outcome;
        }

        public NBTTagCompound root() {
            return root;
        }

        /** Present only when the original was successfully copied aside. */
        public Optional<String> quarantinePath() {
            return Optional.ofNullable(quarantinePath);
        }
    }
}
