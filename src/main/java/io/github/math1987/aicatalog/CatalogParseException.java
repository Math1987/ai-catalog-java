package io.github.math1987.aicatalog;

/** A parsing failure. Messages deliberately omit input values and source excerpts. */
public final class CatalogParseException extends IllegalArgumentException {
    public enum Code {
        INVALID_JSON,
        INVALID_STRUCTURE,
        UNSUPPORTED_SPEC_VERSION,
        INPUT_LIMIT_EXCEEDED
    }

    private final Code code;
    private final String pointer;

    CatalogParseException(Code code, String pointer, String message) {
        super(message);
        this.code = code;
        this.pointer = pointer;
    }

    public Code code() {
        return code;
    }

    /** JSON Pointer to a structural failure; empty for root or syntax failures. */
    public String pointer() {
        return pointer;
    }
}
