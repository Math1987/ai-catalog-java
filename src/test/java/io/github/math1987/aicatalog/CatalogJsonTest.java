package io.github.math1987.aicatalog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CatalogJsonTest {
    private final CatalogJson codec = new CatalogJson();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @TestFactory
    Stream<DynamicTest> coreEnvelopeFixtures() throws IOException {
        var cases = json.readTree(resource("core-envelope.json")).get("cases");
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode fixture : cases) {
            tests.add(DynamicTest.dynamicTest(fixture.get("name").asString(), () -> {
                String source = fixture.get("json").asString();
                if (fixture.get("accepted").asBoolean()) {
                    var document = codec.read(source);
                    assertEquals(json.readTree(source), json.readTree(codec.write(document)));
                    assertEquals(codec.write(document), codec.write(codec.read(source.getBytes(StandardCharsets.UTF_8))));
                } else {
                    var error = assertThrows(CatalogParseException.class, () -> codec.read(source));
                    assertEquals(fixture.get("code").asString(), error.code().name());
                    assertEquals(fixture.get("pointer").asString(), error.pointer());
                    var bytesError = assertThrows(CatalogParseException.class,
                            () -> codec.read(source.getBytes(StandardCharsets.UTF_8)));
                    assertEquals(error.code(), bytesError.code());
                    assertEquals(error.pointer(), bytesError.pointer());
                }
            }));
        }
        return tests.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "123", "\"text\"", "[]", "{}"})
    void acceptsEveryInlineJsonKind(String data) {
        var document = codec.read(catalog(data));
        var entry = document.entries().get(0);
        assertEquals("x", entry.identifier());
        assertEquals("custom", entry.type());
        assertTrue(entry.url().isEmpty());
        assertEquals(data, entry.dataJson().orElseThrow());
    }

    @Test
    void exposesUrlWithoutResolvingIt() {
        var document = codec.read("""
                {"specVersion":"1.0","entries":[{"identifier":"x","type":"custom","url":"../card.json"}]}
                """);
        assertEquals("../card.json", document.entries().get(0).url().orElseThrow());
        assertTrue(document.entries().get(0).dataJson().isEmpty());
    }

    @Test
    void preservesUnknownValuesEmptyCollectionsSignaturesAndNumbers() throws IOException {
        byte[] source = resource("preserved-catalog.json");
        var document = codec.read(source);
        JsonNode written = json.readTree(codec.write(document));
        assertEquals(json.readTree(source), written);
        assertEquals(new BigInteger("9007199254740993"), written.at("/futureRoot/integer").bigIntegerValue());
        assertEquals(new BigDecimal("1.234567890123456789"), written.at("/futureRoot/decimal").decimalValue());
        assertEquals("1.0", document.specVersion());
        assertThrows(UnsupportedOperationException.class, () -> document.entries().clear());
        assertEquals(written, json.readTree(codec.write(codec.read(codec.write(document)))));
    }

    @Test
    void rejectsMalformedUtf8WithoutReplacingBytes() {
        var error = assertThrows(CatalogParseException.class,
                () -> codec.read(new byte[] {(byte) 0xc3, 0x28}));
        assertEquals(CatalogParseException.Code.INVALID_JSON, error.code());
        assertThrows(CatalogParseException.class, () -> codec.read("\ud800"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\ud800", "\\udfff", "\\ud800x", "\\ud800\\ud800",
            "\\udfff\\ud800", "\\ud800\\udfff\\ud800"})
    void rejectsUnpairedSurrogateEscapesThroughoutTheDocument(String escaped) {
        String quoted = "\"" + escaped + "\"";
        String[] inputs = {
                catalog(quoted),
                catalog("{\"future\":[" + quoted + "]}"),
                catalog("{" + quoted + ":null}"),
                "{\"specVersion\":\"1.0\",\"entries\":[],\"future\":{\"nested\":[" + quoted + "]}}",
                "{\"specVersion\":\"1.0\",\"entries\":[{\"identifier\":" + quoted
                        + ",\"type\":\"custom\",\"data\":null}]}",
                "{\"specVersion\":\"1.0\",\"entries\":[{\"identifier\":\"x\",\"type\":"
                        + quoted + ",\"data\":null}]}",
                "{\"specVersion\":\"1.0\",\"entries\":[{\"identifier\":\"x\",\"type\":\"custom\",\"url\":"
                        + quoted + "}]}"
        };
        for (String input : inputs) {
            var error = assertThrows(CatalogParseException.class, () -> codec.read(input));
            assertEquals(CatalogParseException.Code.INVALID_JSON, error.code());
            assertEquals("", error.pointer());
            assertFalse(error.getMessage().contains(escaped));
            assertNull(error.getCause());
            var bytesError = assertThrows(CatalogParseException.class,
                    () -> codec.read(input.getBytes(StandardCharsets.UTF_8)));
            assertEquals(error.code(), bytesError.code());
        }
    }

    @Test
    void preservesPairedSurrogateEscapesInNamesAndValues() {
        var document = codec.read(catalog("{\"\\ud800\\udfff\":\"\\ud83c\\udf0d\"}"));
        String written = codec.write(document);
        assertEquals("{\"\ud800\udfff\":\"\ud83c\udf0d\"}", document.entries().get(0).dataJson().orElseThrow());
        assertEquals(written, codec.write(codec.read(written)));
        assertEquals(written, codec.write(codec.read(written.getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e2147483648", "1e-2147483648", "0e2147483648",
            "0.000000000000000001e-2147483647"})
    void reportsUnsupportedNumericRangesWithoutLeakingInput(String number) {
        String source = catalog(number);
        var error = assertThrows(CatalogParseException.class, () -> codec.read(source));
        assertEquals(CatalogParseException.Code.INPUT_LIMIT_EXCEEDED, error.code());
        assertEquals("", error.pointer());
        assertFalse(error.getMessage().contains(number));
        assertNull(error.getCause());
        var bytesError = assertThrows(CatalogParseException.class,
                () -> codec.read(source.getBytes(StandardCharsets.UTF_8)));
        assertEquals(error.code(), bytesError.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e400", "-1e400", "1e-400", "1e2147483647",
            "1e-2147483647", "10e2147483647"})
    void preservesRepresentableExtremeNumbers(String number) {
        var document = codec.read(catalog(number));
        String inline = document.entries().get(0).dataJson().orElseThrow();
        assertEquals(0, new BigDecimal(number).compareTo(json.readTree(inline).decimalValue()));
        assertEquals(inline, codec.read(codec.write(document)).entries().get(0).dataJson().orElseThrow());
    }

    @Test
    void doesNotExposeInputInSyntaxErrors() {
        String secret = "sensitive_value_never_echo";
        var error = assertThrows(CatalogParseException.class,
                () -> codec.read("{\"" + secret + "\": invalid}"));
        assertFalse(error.getMessage().contains(secret));
        assertTrue(error.getMessage().contains("line"));
        assertNull(error.getCause());
    }

    @Test
    void preservesHigherMinorVersionSpelling() {
        assertEquals("01.099", codec.read("{\"specVersion\":\"01.099\",\"entries\":[]}").specVersion());
    }

    @Test
    void doesNotPerformReleaseUniquenessValidation() {
        String entry = "{\"identifier\":\"x\",\"type\":\"custom\",\"data\":null}";
        var document = codec.read("{\"specVersion\":\"1.0\",\"entries\":[" + entry + "," + entry + "]}");
        assertEquals(2, document.entries().size());
    }

    static String catalog(String data) {
        return "{\"specVersion\":\"1.0\",\"entries\":[{\"identifier\":\"x\",\"type\":\"custom\",\"data\":" + data + "}]}";
    }

    private byte[] resource(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }
}
