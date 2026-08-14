package com.github.postyizhan.betterquesting.platform.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.postyizhan.betterquesting.core.storage.json.JsonDocumentStore;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommonBootstrapLoadOutcomeTest {
    static Stream<Arguments> outcomes() {
        return Stream.of(
            Arguments.of(JsonDocumentStore.Outcome.LOADED, "NameCache.json", "name cache", false,
                "Loaded BetterQuesting NameCache.json"),
            Arguments.of(JsonDocumentStore.Outcome.ABSENT, "NameCache.json", "name cache", false,
                "BetterQuesting NameCache.json absent; using default-empty name cache"),
            Arguments.of(JsonDocumentStore.Outcome.QUARANTINED, "NameCache.json", "name cache", true,
                "BetterQuesting NameCache.json quarantined; name cache empty, writes disabled"),
            Arguments.of(JsonDocumentStore.Outcome.LOADED, "LifeDatabase.json", "life database", false,
                "Loaded BetterQuesting LifeDatabase.json"),
            Arguments.of(JsonDocumentStore.Outcome.ABSENT, "LifeDatabase.json", "life database", false,
                "BetterQuesting LifeDatabase.json absent; using default-empty life database"),
            Arguments.of(JsonDocumentStore.Outcome.QUARANTINED, "LifeDatabase.json", "life database", true,
                "BetterQuesting LifeDatabase.json quarantined; life database empty, writes disabled")
        );
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    void identityDatabaseOutcomeSelectsAccurateLevelAndMessage(JsonDocumentStore.Outcome outcome,
        String document, String emptyState, boolean warning, String message) {
        CommonBootstrap.LoadLog decision = CommonBootstrap.identityDatabaseLoadLog(
            outcome, document, emptyState);

        assertEquals(warning, decision.warning());
        assertEquals(message, decision.message());
    }
}
