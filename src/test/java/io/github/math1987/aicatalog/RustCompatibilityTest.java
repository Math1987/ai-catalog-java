package io.github.math1987.aicatalog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Replays recorded Rust core results; no Rust executable or validator runs in Maven. */
class RustCompatibilityTest {
    private final CatalogJson codec = new CatalogJson();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @TestFactory
    Stream<DynamicTest> matchesRecordedRustCoreResults() throws IOException {
        final JsonNode fixtures;
        try (var stream = getClass().getResourceAsStream("/rust-reference.json")) {
            assertNotNull(stream);
            fixtures = json.readTree(stream);
        }
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode fixture : fixtures.get("cases")) {
            tests.add(DynamicTest.dynamicTest(fixture.get("name").asString(), () -> {
                String source = fixture.get("source").asString();
                JsonNode observed = fixture.get("rust");
                var textDocument = codec.read(source);
                var byteDocument = codec.read(source.getBytes(StandardCharsets.UTF_8));
                assertRecordedResult(observed, textDocument);
                assertRecordedResult(observed, byteDocument);
                assertEquals(codec.write(textDocument), codec.write(byteDocument));
            }));
        }
        return tests.stream();
    }

    private void assertRecordedResult(JsonNode observed, CatalogDocument actual) {
        assertEquals(observed.get("spec_version").asString(), actual.specVersion());
        assertEquals(json.readTree(observed.get("roundtrip_json").asString()),
                json.readTree(codec.write(actual)));
        JsonNode expectedEntries = observed.get("entries");
        assertEquals(expectedEntries.size(), actual.entries().size());
        for (int i = 0; i < expectedEntries.size(); i++) {
            JsonNode expected = expectedEntries.get(i);
            CatalogEntry entry = actual.entries().get(i);
            assertEquals(expected.get("identifier").asString(), entry.identifier());
            assertEquals(expected.get("type").asString(), entry.type());
            JsonNode url = expected.get("url");
            assertEquals(url.isNull() ? null : url.asString(), entry.url().orElse(null));
            JsonNode data = expected.get("data_json");
            if (data.isNull()) {
                assertTrue(entry.dataJson().isEmpty());
            } else {
                assertEquals(json.readTree(data.asString()),
                        json.readTree(entry.dataJson().orElseThrow()));
            }
        }
    }
}
