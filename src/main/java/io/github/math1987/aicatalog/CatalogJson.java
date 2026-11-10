package io.github.math1987.aicatalog;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bounded, offline parsing of the AI Catalog core envelope.
 * Optional metadata is preserved without semantic or cryptographic validation.
 */
public final class CatalogJson {
    private static final Pattern SPEC_VERSION = Pattern.compile("([0-9]+)\\.([0-9]+)");
    private final Limits limits;
    private final JsonMapper mapper;

    /** Local resource policy; these ceilings are not specification requirements. */
    public record Limits(int maxInputBytes, int maxNestingDepth, int maxStringLength,
                         int maxNumberLength, int maxTokens) {
        public Limits {
            if (maxInputBytes < 1 || maxNestingDepth < 1 || maxStringLength < 1
                    || maxNumberLength < 1 || maxTokens < 1) {
                throw new IllegalArgumentException("All input limits must be positive.");
            }
        }

        public static Limits defaults() {
            return new Limits(1_048_576, 64, 262_144, 128, 100_000);
        }
    }

    public CatalogJson() {
        this(Limits.defaults());
    }

    public CatalogJson(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        var factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limits.maxInputBytes())
                        .maxNestingDepth(limits.maxNestingDepth())
                        .maxStringLength(limits.maxStringLength())
                        .maxNameLength(limits.maxStringLength())
                        .maxNumberLength(limits.maxNumberLength())
                        .maxTokenCount(limits.maxTokens())
                        .build())
                .build();
        mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    /** Reads UTF-8 JSON. Invalid byte sequences and oversized input are rejected. */
    public CatalogDocument read(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        checkSize(utf8.length);
        try {
            // Decode explicitly to reject malformed UTF-8 and other JSON encodings.
            return parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)).toString());
        } catch (CharacterCodingException e) {
            throw invalidEncoding();
        }
    }

    /** Reads JSON text, applying the same UTF-8 byte ceiling as the byte-array API. */
    public CatalogDocument read(String json) {
        Objects.requireNonNull(json, "json");
        checkSize(json.length());
        try {
            checkSize(StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json)).remaining());
        } catch (CharacterCodingException e) {
            throw invalidEncoding();
        }
        return parse(json);
    }

    /** Preserves JSON values and member presence, not whitespace or numeric spelling. */
    public String write(CatalogDocument document) {
        return mapper.writeValueAsString(Objects.requireNonNull(document, "document").root());
    }

    private CatalogDocument parse(String json) {
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (StreamConstraintsException e) {
            throw new CatalogParseException(CatalogParseException.Code.INPUT_LIMIT_EXCEEDED,
                    "", "JSON exceeds configured processing limits.");
        } catch (JacksonException e) {
            var location = e.getLocation();
            String where = location == null ? "" : " at line " + location.getLineNr()
                    + ", column " + location.getColumnNr();
            throw new CatalogParseException(CatalogParseException.Code.INVALID_JSON,
                    "", "Invalid JSON" + where + ".");
        }
        if (root == null || root.isMissingNode()) {
            throw new CatalogParseException(CatalogParseException.Code.INVALID_JSON, "", "JSON is empty.");
        }
        require(root.isObject(), "", "Catalog must be an object.");
        String version = string(root, "specVersion", "");
        var match = SPEC_VERSION.matcher(version);
        require(match.matches(), "/specVersion", "specVersion must use Major.Minor format.");
        if (!match.group(1).matches("0*1")) {
            throw new CatalogParseException(CatalogParseException.Code.UNSUPPORTED_SPEC_VERSION,
                    "/specVersion", "Only specification major version 1 is supported.");
        }
        JsonNode items = root.get("entries");
        require(items != null && items.isArray(), "/entries", "entries must be an array.");
        var entries = new ArrayList<CatalogEntry>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String path = "/entries/" + i;
            require(item.isObject(), path, "Entry must be an object.");
            String identifier = string(item, "identifier", path);
            String type = string(item, "type", path);
            boolean hasUrl = item.has("url");
            boolean hasData = item.has("data");
            require(hasUrl != hasData, path, "Entry must contain exactly one of url or data.");
            String url = hasUrl ? string(item, "url", path) : null;
            String data = hasData ? mapper.writeValueAsString(item.get("data")) : null;
            entries.add(new CatalogEntry(identifier, type, url, data));
        }
        return new CatalogDocument((ObjectNode) root, entries);
    }

    private static String string(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        require(value != null && value.isString(), path + "/" + field, field + " must be a string.");
        return value.asString();
    }

    private static void require(boolean condition, String pointer, String message) {
        if (!condition) {
            throw new CatalogParseException(CatalogParseException.Code.INVALID_STRUCTURE, pointer, message);
        }
    }

    private void checkSize(int bytes) {
        if (bytes > limits.maxInputBytes()) {
            throw new CatalogParseException(CatalogParseException.Code.INPUT_LIMIT_EXCEEDED,
                    "", "JSON exceeds the configured UTF-8 byte limit.");
        }
    }

    private static CatalogParseException invalidEncoding() {
        return new CatalogParseException(CatalogParseException.Code.INVALID_JSON,
                "", "Input must be valid UTF-8 text.");
    }
}
