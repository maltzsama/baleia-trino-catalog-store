# Baleia OPA policy

Baleia's data policies (row filters and column masks, Baleia ADR 0028) run in
OPA next to the Trino coordinator. This directory holds the policy code that
the plugin release ships, so it is versioned and checksummed together with the
plugin (Baleia ADR 0004).

```
opa/
├── baleia/
│   ├── core/            # package baleia.core: the policy semantics
│   ├── trino/           # package baleia.trino: the adapter for Trino's OPA input
│   └── testdata/        # a sample bundle and OPA inputs recorded from Trino 484
├── build-bundle.sh      # builds baleia-opa-policy.tar.gz and prints its digest
└── opa-access-control.properties
```

The Rego is a copy of `deploy/opa/baleia` in the Baleia repository, which
embeds the same files to compute the digest it expects OPA to run. Change both
together; Baleia's tests compare its digest with the revision of the bundle in
the plugin release it pins.

## What the release tarball carries

The adapter is tested against the OPA input recorded from Trino 484
(`baleia/testdata/trino484`), so only the `+trino484` tarball carries the
policy: its `VERSION` names the Trino release the Rego was recorded against.
`baleia-catalog-store-<version>+trino484.tar.gz` has an `opa/` directory,
covered by the tarball's `.sha256`:

| File | What it is |
|---|---|
| `opa/baleia-opa-policy.tar.gz` | The OPA bundle with the Rego of `baleia.core` and `baleia.trino`, tests and test data left out. Its manifest roots are `baleia/core` and `baleia/trino`, so a data bundle cannot replace the code. |
| `opa/REVISION` | The bundle's manifest revision: the policy digest. |
| `opa/opa-access-control.properties` | Trino's OPA access control with all five policy URIs, pointing at OPA on `127.0.0.1:8181`. |

The policy digest is the SHA-256 of the sorted `<path> <sha256>` lines of the
Rego files in the bundle (paths such as `baleia/core/core.rego`). OPA reports
it as the policy bundle's active revision, and Baleia compares the two.

Load the bundle as OPA's `policy` bundle from a local file, for example
`resource: file:///policy/policy.tar.gz`, next to the signed data bundle that
Baleia serves. The Baleia chart does this; an operator of an external Trino
follows Baleia's operator documentation.

## Checks

CI runs, with the OPA release the Baleia chart pins:

```bash
opa check --strict opa/baleia
opa fmt --list --fail opa/baleia
opa test -v opa/baleia
opa/build-bundle.sh /tmp/baleia-opa-policy.tar.gz
```

The bundle is deterministic: the same sources always give the same bytes and
the same digest.
