# Rust compatibility review

Reviewed on 2026-11-10 against Rust SDK `0.4.0`, commit
[`920ef70`](https://github.com/Agent-Card/ai-catalog-rust/tree/920ef70e1e5a602e142137f9ea6a1135fedbf110),
and specification revision
[`9aac2c1`](https://github.com/Agent-Card/ai-catalog/blob/9aac2c1bfa622356e45a31f3dc1d342bdb329e00/specification/ai-catalog.md).
The Rust repository's remote HEAD matched the reviewed commit.

## Conclusion and scope

The shared core features agree on ordinary catalogs: entry order, identifier and
type strings, declared URLs, non-null inline JSON, and unknown members. Universal
Rust/Java equivalence would be an incorrect claim. Rust's ordinary model serializer
changes some member presence and numeric values; Java deliberately preserves them.
The implementations also have different parsing and validation boundaries.

This review covers string/UTF-8 parsing, serialization, the Java core accessors,
malformed input and resource limits. It does not establish parity for discovery,
HTTP, signing, verification, JCS, OCI, search, authoring or full semantic validation.
Those APIs are outside the Java implementation's first slice.

## Method

The differential corpus contains 133 distinct inputs, including the 13 files in
Rust's `fixtures/` directory (some are negative or shape fixtures), the existing
Java envelope fixtures, and additional presence, Unicode, numeric, duplicate-key,
version and limit probes. Five supplementary decimal cases exercise Rust's
dependency-dependent floating-point behavior.

Rust was executed in two builds:

- Core only: `ai-catalog`, with `serde_json 1.0.154` features `default,std`.
- Core plus `ai-catalog-validate`: the validator's trust dependency enables
  `serde_json/float_roundtrip` through Cargo feature unification. Parsing and
  `validate().is_valid` were recorded separately.

The comparison parses numeric output as arbitrary-precision decimals. It compares
JSON values and member presence, preserves array order, and ignores object-member
order, whitespace and numeric spelling. It also compares each entry's identifier,
type, optional URL, and optional serialized inline data. An absent data value and
the present JSON value `null` are distinct.

Java was checked through both input APIs and a second read/write round trip. Rust
was checked through `parse_str`, `parse_slice` and a second serialization. These
are behavioral observations of pinned implementations, not conformance certification.

## Observed results

| Result in the 133-case main corpus | Count |
| --- | ---: |
| Accepted by both readers | 59 |
| Rejected by both readers | 32 |
| Accepted only by Java | 7 |
| Accepted only by Rust core | 35 |
| Equal complete JSON output among the 59 shared acceptances | 43 |
| Equal core accessor values among the 59 shared acceptances | 52 |

All 66 inputs accepted by the corrected Java reader retained their input JSON
values and member presence. Their byte/string results matched and their second
round trips were stable. Every Java rejection used `CatalogParseException`.
Rust's 94 accepted cases also had equivalent string/byte results and stable
second serializations; stability alone does not imply preservation of the input.

The differences below account for the observed mismatches. Counts are descriptive
of this adversarial corpus and are not a compatibility percentage for real traffic.

| Area | Java behavior | Rust behavior | Review decision |
| --- | --- | --- | --- |
| Required core fields | Requires version, entries, identifier and type | Core deserialization supplies defaults for absent fields; separate validation catches most omissions, but accepts absent entries | Keep Java's envelope checks; the spec requires entries |
| Inline `data: null` | Present data value; survives serialization | `Option<Value>` becomes `None`; serialization removes it and validation reports no source | Keep explicit null; the spec permits any JSON data value |
| `url` plus `data: null` | Rejects two source members | Null becomes absent, so validation accepts a URL-only entry | Keep Java's member-presence rule |
| Empty optional arrays, explicit null metadata | Retains members exactly | Model serialization omits empty arrays and null `Option` fields | Keep preservation; do not copy lossy output |
| Empty host/publisher/signature objects | Retains them as opaque metadata | Inserts default empty strings; validator rejects missing required content | Intentional envelope-only scope; Java acceptance is not document validity |
| Wrong optional metadata types | Retains them without interpreting them | Typed Rust deserialization rejects, for example, `host:7` or `tags:null` | Intentional scope difference, clearly documented |
| Duplicate members | Rejects at every nesting level | Known root/entry duplicates reject; unknown members, inline maps and manifest maps can use last value | Keep global duplicate rejection as a defensive parser policy |
| Version spelling and support | Decimal digit components, only major 1; minor is not limited to `u64` | Core accepts arbitrary strings; validator uses `u64`, accepts major 0 and leading `+`, rejects overflowing minor components | Document parser/validator and support-policy differences |
| Large integers and precise decimals | Retains exact values within the supported numeric range | Outside integer storage range, uses floating-point; decimal values can round | Keep exact Java values |
| Extreme exponents | Preserves representable values such as `1e400`; rejects decimal-range overflow with a typed limit error | Positive overflow can reject; tiny magnitudes such as `1e-400` can become zero | Document supported representation; never substitute zero or infinity |
| Input limits | Explicit byte, depth, string/name, number-token and token ceilings | Core has serde's recursion ceiling, without the other Java ceilings | Local resource policy; identical acceptance is not expected |
| Empty strings, duplicate releases, nested catalog semantics | Only core shapes are checked; artifact data stays opaque | Separate validator applies semantic rules, including nested catalogs | Do not present parsing as full validation |

Rust's ordinary serializer is not its signature-preservation path.
[`TrustManifest::decoded_json()`](https://github.com/Agent-Card/ai-catalog-rust/blob/920ef70e1e5a602e142137f9ea6a1135fedbf110/crates/ai-catalog/src/model.rs#L211)
retains an original decoded snapshot for unchanged manifests. The observations
above do not demonstrate a defect in Rust signature verification. Java does not
implement that feature or claim signed-payload/JCS equivalence.

### Dependency-dependent decimals

For input `51.248178375505404`, the core-only Rust build writes
`51.24817837550541`; the validator-enabled Rust build and Java retain
`51.248178375505404`. The same distinction appeared in all five supplementary
decimal probes. Therefore a comparison that adds the Rust validator dependency
without recording Cargo features can conceal differences in the core SDK.

The reviewed Rust model and parser are available in
[`model.rs`](https://github.com/Agent-Card/ai-catalog-rust/blob/920ef70e1e5a602e142137f9ea6a1135fedbf110/crates/ai-catalog/src/model.rs)
and [`lib.rs`](https://github.com/Agent-Card/ai-catalog-rust/blob/920ef70e1e5a602e142137f9ea6a1135fedbf110/crates/ai-catalog/src/lib.rs).

## Java defects fixed during review

1. **Unpaired Unicode escapes broke round trips.** A string such as `"\ud800"`
   parsed, but serialization produced an unpaired UTF-16 surrogate that the UTF-8
   input API rejected. Values and member names now reject unpaired surrogates,
   including in opaque metadata. Valid supplementary characters remain supported.
2. **Extreme exponent/scale values escaped the error contract.** Inputs such as
   `1e2147483648` could leak `NumberFormatException` with input text. They now
   produce sanitized `INPUT_LIMIT_EXCEEDED`, with no input-bearing cause.
3. **Configured depth was inconsistent between reading and writing.** Increasing
   read depth beyond 500 could hit Jackson's independent default write ceiling.
   The same configured depth now applies to serialization, and a stricter writer
   reports a typed limit error when given a document from a more permissive reader.

Regression tests cover each defect, byte/string agreement, supplementary Unicode,
representable extreme numbers, and custom-depth inline and unknown metadata.
No additional production feature was added to obtain Rust parity.

## Ongoing regression coverage

`src/test/resources/rust-reference.json` records a compact selection of matching
Rust core results with their source revision. `RustCompatibilityTest` checks Java
against those recorded outputs and accessor values during `./mvnw verify`.
Maven tests do not execute Rust or download a Rust toolchain. Refreshing the Rust
reference revision requires rerunning the differential comparison and reviewing
each changed observation; it must not silently redefine Java's preservation rules.
