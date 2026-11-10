# AI Catalog Java

Read an AI Catalog, inspect its core entry fields, and write it back while preserving unmodeled JSON values. Requires Java 17 or later.

This project is independently maintained and is not an official Agent-Card SDK.

## Usage

```java
import io.github.math1987.aicatalog.CatalogJson;

var json = new CatalogJson();
var catalog = json.read("""
    {
      "specVersion": "1.0",
      "entries": [{
        "identifier": "urn:air:example.com:agent:research",
        "type": "application/a2a-agent-card+json",
        "url": "https://example.com/agent-card.json"
      }]
    }
    """);

System.out.println(catalog.specVersion());
for (var entry : catalog.entries()) {
    System.out.println(entry.identifier());
    entry.url().ifPresent(System.out::println);
    entry.dataJson().ifPresent(System.out::println);
}
String output = json.write(catalog);
```

`read` accepts a JSON string or UTF-8 bytes. The result is read-only; the public API does not expose Jackson types. Exactly one of `url()` and `dataJson()` is present. An inline JSON null is represented by the present string `"null"`, rather than an empty optional. URLs are returned as declared, including relative references.

The artifact is currently an unpublished `0.1.0-SNAPSHOT`. To install locally, run `./mvnw install`; its coordinates are `io.github.math1987:ai-catalog-java:0.1.0-SNAPSHOT`.

## Parsing boundary

This first slice implements **core-envelope parsing**, not complete specification validation. It checks:

- a root object with a `Major.Minor` string `specVersion` and an `entries` array;
- specification major `1`, accepting higher minor versions;
- object entries with string `identifier` and `type` fields;
- exactly one of a string `url` or an arbitrary JSON `data` value;
- valid JSON syntax, unique member names, and configured input limits.

Required string checks enforce types, not additional naming or media-type restrictions. Empty catalogs and custom artifact types are supported. Unknown members, optional metadata, Trust Manifests and signature fields are retained without validating their structures or meaning. Successful parsing does not establish full conformance, artifact integrity or trust. There is no HTTP fetching, signing, verification, release-uniqueness validation, or artifact-specific parsing.

Serialization preserves JSON values and member presence, including nulls, empty collections, large integers and decimals. Whitespace, object-member ordering and numeric spelling are not guaranteed. It is not canonicalization and makes no claim about signature validity.

The implementation is pinned to [specification revision `9aac2c1`](https://github.com/Agent-Card/ai-catalog/blob/9aac2c1bfa622356e45a31f3dc1d342bdb329e00/specification/ai-catalog.md). Global duplicate-key rejection is a defensive parser policy; that revision specifically requires rejection when processing signed payloads. Optional-field validation and cryptography are separate future work.

## Input limits and errors

The default policy caps UTF-8 input at 1 MiB, nesting at 64, strings and property names at 262,144 characters, number tokens at 128 characters, and tokens at 100,000. The byte ceiling is checked before parsing; the remaining limits use Jackson's processing constraints, which are not all exact allocation boundaries. Limits are local resource policy, not specification requirements. The configured nesting ceiling also applies when writing: a document read by a more permissive instance can exceed another instance's write limit.

Strings and property names must contain valid Unicode scalar values, including after decoding JSON escapes. Unpaired surrogate escapes are rejected. Numbers retain their values within the supported arbitrary-precision decimal range; exponent or scale overflow produces `INPUT_LIMIT_EXCEEDED`, even when the number token is short.

For a different policy, construct `CatalogJson` with a `CatalogJson.Limits` value. The library uses its own JSON-tree parser and does not enable polymorphic Java-object deserialization or modify global Jackson configuration.

Failures throw `CatalogParseException` with a stable `code()` and a JSON Pointer in `pointer()` for structural errors. Syntax-error messages include a line and column where available. Errors omit source excerpts and input values.

## Development

```sh
./mvnw verify
```

The wrapper pins Maven and verifies its distribution checksum. CI runs on patched Temurin Java 17 and 25. Jackson is the only direct runtime dependency; JUnit is test-only. Dependency review checks pull requests and Dependabot proposes dependency updates.

The language-neutral inputs in [`src/test/resources/core-envelope.json`](src/test/resources/core-envelope.json) record their specification revision and parsing stage. They do not assert full document validity. Tests also cover round-trip preservation, UTF-8 decoding, number precision, and processing limits. The [Rust comparison](docs/rust-compatibility.md) records the reviewed versions, shared behavior and intentional differences; this envelope reader is not a replacement for Rust's full validator or trust APIs.

Licensed under Apache-2.0. The upstream specification is separately licensed under [CC BY 4.0](https://github.com/Agent-Card/ai-catalog/blob/9aac2c1bfa622356e45a31f3dc1d342bdb329e00/LICENSE.md); this repository is an independent implementation.
