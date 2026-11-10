package io.github.math1987.aicatalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CatalogLimitsTest {
    @Test
    void enforcesExactUtf8ByteLimitForBothInputApis() {
        String input = CatalogJsonTest.catalog("\"café 🌍\"");
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        var exact = codec(bytes.length, 64, 262_144, 128, 100_000);
        assertDoesNotThrow(() -> exact.read(input));
        assertDoesNotThrow(() -> exact.read(bytes));
        var shortLimit = codec(bytes.length - 1, 64, 262_144, 128, 100_000);
        limited(() -> shortLimit.read(input));
        limited(() -> shortLimit.read(bytes));
    }

    @Test
    void limitsNestingInsideOpaqueData() {
        var codec = codec(100_000, 8, 10_000, 128, 10_000);
        String input = CatalogJsonTest.catalog("[".repeat(20) + "0" + "]".repeat(20));
        limited(() -> codec.read(input));
    }

    @Test
    void limitsStringValues() {
        var codec = codec(100_000, 64, 32, 128, 10_000);
        limited(() -> codec.read(CatalogJsonTest.catalog("\"" + "x".repeat(10_000) + "\"")));
    }

    @Test
    void limitsPropertyNames() {
        var codec = codec(100_000, 64, 32, 128, 10_000);
        limited(() -> codec.read(CatalogJsonTest.catalog("{\"" + "x".repeat(10_000) + "\":null}")));
    }

    @Test
    void limitsNumbersBeforeMaterialization() {
        var codec = codec(100_000, 64, 10_000, 16, 10_000);
        limited(() -> codec.read(CatalogJsonTest.catalog("9".repeat(500))));
    }

    @Test
    void limitsTokenCountInWideInputs() {
        var codec = codec(100_000, 64, 10_000, 128, 30);
        limited(() -> codec.read(CatalogJsonTest.catalog("[" + "0,".repeat(100) + "0]")));
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> codec(0, 64, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> codec(100, -1, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 64, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 64, 10, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 64, 10, 10, 0));
    }

    private static CatalogJson codec(int bytes, int depth, int strings, int numbers, int tokens) {
        return new CatalogJson(new CatalogJson.Limits(bytes, depth, strings, numbers, tokens));
    }

    private static void limited(org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(CatalogParseException.class, operation);
        assertEquals(CatalogParseException.Code.INPUT_LIMIT_EXCEEDED, error.code());
    }
}
