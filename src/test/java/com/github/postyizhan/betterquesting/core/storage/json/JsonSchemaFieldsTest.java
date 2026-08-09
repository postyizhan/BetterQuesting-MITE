package com.github.postyizhan.betterquesting.core.storage.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.minecraft.NBTTagCompound;
import org.junit.jupiter.api.Test;

class JsonSchemaFieldsTest {
    private final NbtJsonCodec codec = new NbtJsonCodec();

    @Test
    void upstreamFormatLiteralIsPreserved() {
        assertEquals("3.1.0", JsonSchemaFields.UPSTREAM_FORMAT);
        assertEquals("format", JsonSchemaFields.FORMAT_KEY);
        assertEquals("build", JsonSchemaFields.BUILD_KEY);
        assertEquals("mitePortFormat", JsonSchemaFields.MITE_PORT_FORMAT_KEY);
    }

    @Test
    void stampWritesAllThreeFieldsAsStringTags() {
        NBTTagCompound root = new NBTTagCompound();

        JsonSchemaFields.stamp(root, "1.0.0");

        assertEquals("{\"build:8\":\"1.0.0\",\"format:8\":\"3.1.0\",\"mitePortFormat:8\":\"1\"}",
            codec.toJson(root, true).toString());
    }

    @Test
    void stampTreatsANullBuildAsEmptySoTheKeyStillExists() {
        NBTTagCompound root = new NBTTagCompound();

        JsonSchemaFields.stamp(root, null);

        assertEquals("", root.getString("build"));
        assertEquals(JsonSchemaFields.LEGACY_BUILD, JsonSchemaFields.readBuild(root));
    }

    @Test
    void absentOrEmptyBuildReadsBackAsUpstreamsLegacyPlaceholder() {
        assertEquals("pre-251", JsonSchemaFields.readBuild(new NBTTagCompound()));
        assertEquals("pre-251", JsonSchemaFields.readBuild(null));
    }

    @Test
    void buildUpgradeComparisonIgnoresCaseLikeUpstream() {
        NBTTagCompound root = new NBTTagCompound();
        JsonSchemaFields.stamp(root, "1.0.0-RC1");

        assertFalse(JsonSchemaFields.isBuildUpgrade(root, "1.0.0-RC1"));
        assertFalse(JsonSchemaFields.isBuildUpgrade(root, "1.0.0-rc1"));
        assertTrue(JsonSchemaFields.isBuildUpgrade(root, "1.1.0"));
    }

    @Test
    void buildUpgradeIsDetectedForAFileWithoutABuildField() {
        // Default-pack files carry format without build (QuestCommandDefaults.java:207 and :342),
        // so a fresh install must still see an upgrade rather than a match.
        NBTTagCompound root = new NBTTagCompound();
        root.setString("format", JsonSchemaFields.UPSTREAM_FORMAT);

        assertTrue(JsonSchemaFields.isBuildUpgrade(root, "1.0.0"));
        assertFalse(JsonSchemaFields.isBuildUpgrade(root, ""));
    }

    @Test
    void mitePortFormatDistinguishesPortWrittenFilesFromUpstreamOnes() throws IOException {
        NBTTagCompound upstreamWritten = codec.toNbt(JsonDocuments.parseObject(
            "{\"format:8\":\"3.1.0\",\"build:8\":\"3.5.328\"}"), true);
        assertEquals("", JsonSchemaFields.readMitePortFormat(upstreamWritten));

        NBTTagCompound portWritten = new NBTTagCompound();
        JsonSchemaFields.stamp(portWritten, "1.0.0");
        assertEquals("1", JsonSchemaFields.readMitePortFormat(portWritten));
    }

    @Test
    void stampedFieldsSurviveAFormatModeRoundTrip() throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        JsonSchemaFields.stamp(root, "1.0.0");

        NBTTagCompound restored = codec.toNbt(
            JsonDocuments.parseObject(codec.toJson(root, true).toString()), true);

        assertEquals("3.1.0", restored.getString("format"));
        assertEquals("1.0.0", restored.getString("build"));
        assertEquals("1", restored.getString("mitePortFormat"));
    }
}
