package com.github.postyizhan.betterquesting.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.postyizhan.betterquesting.api.properties.NativeProps;
import com.github.postyizhan.betterquesting.core.storage.DirectoryWorldStorage;
import com.github.postyizhan.betterquesting.core.storage.json.NbtJsonCodec;
import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestSettingsPersistenceTest {
    @TempDir
    Path dataDirectory;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void missingSettingsUseExistingDefaults() throws IOException {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "stale");

        QuestSettingsPersistence persistence = persistence(settings);
        assertEquals(JsonDocumentStore.Outcome.ABSENT, persistence.load());

        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertEquals(3, settings.getProperty(NativeProps.LIVES_DEF));
        assertTrue(settings.getProperty(NativeProps.EDIT_MODE));
    }

    @Test
    void validSettingsLoadIntoTheExistingContainer() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestSettings.json"),
            "{\"betterquesting:10\":{\"pack_name:8\":\"Pack\",\"livesDef:3\":7},"
                + "\"format:8\":\"3.1.0\"}");
        QuestSettings settings = new QuestSettings();

        assertEquals(JsonDocumentStore.Outcome.LOADED, persistence(settings).load());
        assertEquals("Pack", settings.getProperty(NativeProps.PACK_NAME));
        assertEquals(7, settings.getProperty(NativeProps.LIVES_DEF));
        assertTrue(settings.hasProperty(NativeProps.LIVES_MAX));
    }

    @Test
    void malformedSettingsAreQuarantinedAndDefaultsRemainActive() throws IOException {
        Files.writeString(dataDirectory.resolve("QuestSettings.json"), "{\"pack_name:8\":");
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "stale");

        QuestSettingsPersistence persistence = persistence(settings);
        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, persistence.load());
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertTrue(Files.exists(dataDirectory.resolve("malformed_QuestSettings.json.json")));
        assertTrue(Files.exists(dataDirectory.resolve("QuestSettings.json")));
    }

    @Test
    void saveStampsSchemaAndCanBeReadBack() throws IOException {
        QuestSettings settings = new QuestSettings();
        settings.setProperty(NativeProps.PACK_NAME, "Saved");

        QuestSettingsPersistence persistence = persistence(settings);
        persistence.save("1.0.0");

        assertTrue(Files.exists(dataDirectory.resolve("QuestSettings.json")));
        assertFalse(Files.exists(dataDirectory.resolve("QuestSettings.json.tmp")));
        QuestSettings restored = new QuestSettings();
        assertEquals(JsonDocumentStore.Outcome.LOADED, persistence(restored).load());
        assertEquals("Saved", restored.getProperty(NativeProps.PACK_NAME));
        String document = Files.readString(dataDirectory.resolve("QuestSettings.json"));
        assertTrue(document.contains("\"format:8\": \"3.1.0\""));
        assertTrue(document.contains("\"mitePortFormat:8\": \"1\""));
    }

    @Test
    void futurePortRevisionIsQuarantinedRatherThanDowngradedOnSave() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestSettingsPersistence.PATH),
            "{\"betterquesting:10\":{\"pack_name:8\":\"Future\"},"
                + "\"mitePortFormat:8\":\"2\"}");
        QuestSettings settings = new QuestSettings();

        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, persistence(settings).load());
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertTrue(Files.exists(dataDirectory.resolve("malformed_QuestSettings.json.json")));
    }

    @Test
    void malformedTypedPortRevisionIsQuarantined() throws IOException {
        Files.writeString(dataDirectory.resolve(QuestSettingsPersistence.PATH),
            "{\"betterquesting:10\":{\"pack_name:8\":\"Malformed\"},"
                + "\"mitePortFormat:3\":2}");
        QuestSettings settings = new QuestSettings();

        assertEquals(JsonDocumentStore.Outcome.QUARANTINED, persistence(settings).load());
        assertEquals("", settings.getProperty(NativeProps.PACK_NAME));
        assertTrue(Files.exists(dataDirectory.resolve("malformed_QuestSettings.json.json")));
    }

    private QuestSettingsPersistence persistence(QuestSettings settings) {
        JsonDocumentStore store = new JsonDocumentStore(
            new DirectoryWorldStorage(dataDirectory), new NbtJsonCodec(warnings::add), warnings::add);
        return new QuestSettingsPersistence(settings, store);
    }
}
