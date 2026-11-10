package io.github.math1987.aicatalog;

import java.util.Optional;

/** Read-only access to an entry's core fields. Artifact content is opaque. */
public final class CatalogEntry {
    private final String identifier;
    private final String type;
    private final String url;
    private final String dataJson;

    CatalogEntry(String identifier, String type, String url, String dataJson) {
        this.identifier = identifier;
        this.type = type;
        this.url = url;
        this.dataJson = dataJson;
    }

    public String identifier() {
        return identifier;
    }

    public String type() {
        return type;
    }

    /** The declared reference, without normalization, resolution or fetching. */
    public Optional<String> url() {
        return Optional.ofNullable(url);
    }

    /** Inline JSON text; JSON null is the present string {@code "null"}. */
    public Optional<String> dataJson() {
        return Optional.ofNullable(dataJson);
    }
}
