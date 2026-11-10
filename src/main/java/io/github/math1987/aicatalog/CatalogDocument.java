package io.github.math1987.aicatalog;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** An immutable view of a parsed catalog, including retained unmodeled JSON. */
public final class CatalogDocument {
    private final ObjectNode root;
    private final List<CatalogEntry> entries;

    CatalogDocument(ObjectNode root, List<CatalogEntry> entries) {
        this.root = root;
        this.entries = List.copyOf(entries);
    }

    public String specVersion() {
        return root.get("specVersion").asString();
    }

    public List<CatalogEntry> entries() {
        return entries;
    }

    // The parser owns this tree; it is never exposed through the public API.
    ObjectNode root() {
        return root;
    }
}
