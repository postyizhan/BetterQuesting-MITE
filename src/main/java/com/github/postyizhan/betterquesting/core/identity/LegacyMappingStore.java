package com.github.postyizhan.betterquesting.core.identity;

import com.github.postyizhan.betterquesting.platform.api.PlayerIdentity;
import com.github.postyizhan.betterquesting.platform.api.PlayerIdentityResolution;
import com.github.postyizhan.betterquesting.platform.api.WorldStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Durable snapshot of legacy UUID to local identity mappings.
 *
 * <p>Layout: one header line followed by exactly {@code recordCount} record lines. Both line kinds
 * use {@link IdentityRecordCodec}, so each carries its own magic and CRC32.
 *
 * <ul>
 *   <li>Header {@code BQIDMAP1}, 1 payload field: {@code recordCount} (canonical decimal, min 0).</li>
 *   <li>Record {@code BQIDMAPREC1}, 5 payload fields: {@code legacyUuid}, {@code identityUuid},
 *       {@code normalizedUsername}, {@code source}, {@code decision}.</li>
 * </ul>
 *
 * <p>The header count is what makes whole-line truncation or deletion detectable; a per-line
 * checksum alone cannot notice a missing line. Records are written sorted by legacy UUID so the file
 * is byte-stable across runs.
 *
 * <p>Storage is injected and never resolved here, matching the world-lifetime constraint documented
 * on {@link IdentityAuditLog}. Call on the server main thread or serialize externally.
 *
 * <p>No upstream counterpart: upstream keys progress by verifiable Mojang UUIDs and has no mapping
 * table to persist.
 *
 * <p><b>Known gap, not closed by this batch.</b> A header-only file with {@code count=0} is
 * byte-identical to a legitimate empty snapshot, so copying one in from a fresh world silently clears
 * every mapping while every CRC still validates. Per-line checksums cannot detect a valid file
 * replacing another valid file. Closing this requires reconciling the snapshot against the audit
 * log's net effect and refusing to bind when the audit implies mappings the snapshot lacks, which
 * would also give the audit log a read path beyond sequence recovery and rejection reporting. The
 * trap to avoid: a rejected audit line makes the replay incomplete, and an administrator who
 * legitimately removed every mapping is indistinguishable from a swapped snapshot unless "audit
 * incomplete" and "audit complete but disagreeing" are separated first.
 */
public final class LegacyMappingStore {
    public static final String MAPPING_PATH = "identity/LegacyIdentityMappings.txt";
    static final String HEADER_MAGIC = "BQIDMAP1";
    static final int HEADER_PAYLOAD_FIELDS = 1;
    static final String RECORD_MAGIC = "BQIDMAPREC1";
    static final int RECORD_PAYLOAD_FIELDS = 5;

    private final WorldStorage storage;

    public LegacyMappingStore(WorldStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Loads the snapshot. Returns empty only when the file does not exist, which is a fresh world.
     * An existing file that fails any check throws {@link CorruptIdentityMappingException} instead of
     * degrading to an empty map.
     */
    public Optional<Map<UUID, PlayerIdentityResolution>> load() throws IOException {
        byte[] bytes = storage.read(MAPPING_PATH, input -> input.readAllBytes()).orElse(null);
        if (bytes == null) {
            return Optional.empty();
        }

        List<IdentityRecordRejection> rejections = new ArrayList<>();
        List<String> lines = FramedLines.completeLines(bytes);
        if (FramedLines.hasUnterminatedTail(bytes)) {
            rejections.add(new IdentityRecordRejection(lines.size() + 1,
                "trailing bytes are not terminated by LF", FramedLines.unterminatedTail(bytes)));
        }
        if (lines.isEmpty()) {
            rejections.add(new IdentityRecordRejection(1, "mapping file has no header line", ""));
            throw corrupt(rejections);
        }

        long declaredCount = -1;
        try {
            declaredCount = IdentityRecordFields.canonicalLong(
                IdentityRecordCodec.decode(HEADER_MAGIC, HEADER_PAYLOAD_FIELDS, lines.get(0)).get(0),
                "recordCount");
            if (declaredCount < 0) {
                throw new IdentityRecordFormatException("recordCount must not be negative: " + declaredCount);
            }
        } catch (IdentityRecordFormatException invalid) {
            rejections.add(new IdentityRecordRejection(1, invalid.getMessage(), lines.get(0)));
        }

        Map<UUID, PlayerIdentityResolution> mappings = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            try {
                PlayerIdentityResolution resolution = decodeRecord(line);
                UUID legacyUuid = resolution.legacyUuid().orElseThrow();
                if (mappings.put(legacyUuid, resolution) != null) {
                    rejections.add(new IdentityRecordRejection(lineNumber,
                        "duplicate legacy UUID " + legacyUuid, line));
                }
            } catch (IdentityRecordFormatException invalid) {
                rejections.add(new IdentityRecordRejection(lineNumber, invalid.getMessage(), line));
            }
        }

        int actualRecordLines = lines.size() - 1;
        if (declaredCount >= 0 && declaredCount != actualRecordLines) {
            rejections.add(new IdentityRecordRejection(1,
                "header declares " + declaredCount + " records but the file has " + actualRecordLines,
                lines.get(0)));
        }
        if (!rejections.isEmpty()) {
            throw corrupt(rejections);
        }
        return Optional.of(Map.copyOf(mappings));
    }

    /** Replaces the snapshot atomically. A failed write leaves the previous snapshot intact. */
    public void save(Map<UUID, PlayerIdentityResolution> mappings) throws IOException {
        Objects.requireNonNull(mappings, "mappings");
        Map<UUID, PlayerIdentityResolution> sorted = new TreeMap<>(mappings);
        StringBuilder content = new StringBuilder();
        content.append(IdentityRecordCodec.encode(HEADER_MAGIC,
            List.of(Long.toString(sorted.size())))).append('\n');
        for (Map.Entry<UUID, PlayerIdentityResolution> entry : sorted.entrySet()) {
            content.append(encodeRecord(entry.getKey(), entry.getValue())).append('\n');
        }
        byte[] encoded = content.toString().getBytes(StandardCharsets.UTF_8);
        storage.writeAtomically(MAPPING_PATH, (OutputStream output) -> output.write(encoded));
    }

    private static String encodeRecord(UUID legacyUuid, PlayerIdentityResolution resolution) {
        UUID recordedLegacy = resolution.legacyUuid().orElseThrow(
            () -> new IllegalArgumentException("mapping resolution must carry a legacy UUID"));
        if (!recordedLegacy.equals(legacyUuid)) {
            throw new IllegalArgumentException("mapping key " + legacyUuid
                + " does not match the resolution's legacy UUID " + recordedLegacy);
        }
        PlayerIdentity identity = resolution.identity().orElseThrow(
            () -> new IllegalArgumentException("mapping resolution must carry an identity"));
        return IdentityRecordCodec.encode(RECORD_MAGIC, List.of(
            legacyUuid.toString(),
            identity.id().toString(),
            identity.normalizedUsername(),
            resolution.source().name(),
            resolution.decision()));
    }

    private static PlayerIdentityResolution decodeRecord(String line) throws IdentityRecordFormatException {
        List<String> payload = IdentityRecordCodec.decode(RECORD_MAGIC, RECORD_PAYLOAD_FIELDS, line);
        UUID legacyUuid = IdentityRecordFields.canonicalUuid(payload.get(0), "legacyUuid");
        UUID identityUuid = IdentityRecordFields.canonicalUuid(payload.get(1), "identityUuid");
        PlayerIdentity identity = IdentityRecordFields.identity(identityUuid, payload.get(2));
        IdentityRecordFields.adminMappingSource(payload.get(3));
        return IdentityRecordFields.mappedResolution(legacyUuid, identity, payload.get(4));
    }

    private static CorruptIdentityMappingException corrupt(List<IdentityRecordRejection> rejections) {
        return new CorruptIdentityMappingException(
            "BetterQuesting legacy identity mapping file is corrupt", rejections);
    }
}
