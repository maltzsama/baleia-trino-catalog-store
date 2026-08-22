# baleia-trino-catalog-store

A Trino plugin that implements `CatalogStore` and makes the coordinator read catalogs
directly from the `trino_catalog_registry` table in Baleia's PostgreSQL — eliminating
static `.properties` files in `etc/catalog/`.

* **Target Trino version:** 482 (pluggable SPI)
* **Java:** 25+ (tested with `25.0.3-tem`; `air.java.version=25.0.1` is the minimum in Trino's POM)
* **Airlift:** 444 (same as `dep.airlift.version` in Trino)
* **Plugin registration:** ServiceLoader in
  `src/main/resources/META-INF/services/io.trino.spi.Plugin`

The actual SPI 482 signatures are documented in
[`docs/spi-482.md`](docs/spi-482.md) — consult/update it whenever changing Trino version.

## What the plugin does

| When | Trino calls | Plugin does |
|---|---|---|
| Coordinator boot | `getCatalogs()` | `SELECT` enabled rows; per-row: parse, validate name, **eagerly resolve `@baleia-secret[...]`**; bad rows mark `sync_status='error'` and are skipped; total DB failure retries with backoff then fails the boot |
| `CREATE CATALOG` | `createCatalogProperties()` then `addOrReplaceCatalog()` | Resolves `@baleia-secret[...]` (fails the DDL on unresolved/circular reference), `UPSERT`s the row carrying the computed `catalog_version` |
| `DROP CATALOG` | `removeCatalog()` | `UPDATE ... SET enabled = false` (soft delete) |

The plugin does **not** poll — runtime propagation is the backend's responsibility
via `CREATE CATALOG`. It does **not** build connector properties — all knowledge about
Polaris, Iceberg, REST catalog lives in the backend; this plugin only transports.

## Supported Trino versions

| Trino | Compiles | Acceptance | Notes |
|-------|----------|------------|-------|
| 482   | yes      | yes        | floor — version the artifact is built against |
| 483   | yes      | yes        | same artifact |

The plugin compiles against the **lowest** supported version and runs on any
version in the window. One artifact, one release. See
[`docs/spi-compat.md`](docs/spi-compat.md) for the SPI surface, N5 verification,
and upgrade procedure.

**Policy:** support the current and previous Trino releases. When a new version
ships, the floor moves up, the oldest drops out, and the line vanishes from this
table in the same release.

## Backend requirements

The plugin is a **reader**. It queries PostgreSQL for catalogs and resolves secrets.
The database schema belongs to whichever backend manages the catalog lifecycle
(LakeDeepDiver, Cosmonaut, Baleia, or any other). Each backend owns its own
migration — the plugin does not govern schema, run DDL, or claim ownership of
any database object.

### Table names (fixed)

| Table | Purpose |
|---|---|
| `trino_clusters` | Coordinator cluster registry |
| `trino_catalog_registry` | Catalog projection for Trino boot |

Backend new? Create these two tables with these exact names. The plugin joins
them and reads a fixed set of columns — nothing more.

### Boot query

The query the plugin executes on every coordinator start:

```sql
SELECT r.catalog_name, r.connector_name, r.properties::text
  FROM trino_catalog_registry r
  JOIN trino_clusters c ON c.id = r.cluster_id
 WHERE c.name = ? AND r.enabled
 ORDER BY r.catalog_name
```

`?` is the value of `baleia.cluster-name` (default `"default"`).

### Columns the plugin reads

| Table | Column | Type accepted | Required | Notes |
|---|---|---|---|---|
| `trino_clusters` | `name` | `text` | yes | Used in the `WHERE` clause of the boot query |
| `trino_catalog_registry` | `cluster_id` | `uuid` | yes | FK to `trino_clusters.id`; used in the `JOIN` |
| `trino_catalog_registry` | `catalog_name` | `text` | yes | Catalog name in Trino; must match `^[a-z][a-z0-9_]{0,62}$` |
| `trino_catalog_registry` | `connector_name` | `text` | yes | Trino connector name; same format as `catalog_name` |
| `trino_catalog_registry` | `properties` | `json` or `jsonb` | yes | Read via `::text`; must be a flat `{"key":"value"}` object |
| `trino_catalog_registry` | `enabled` | `boolean` | yes | Only `true` rows are loaded at boot |
| `trino_catalog_registry` | `sync_status` | `text` | — | **Written** by the plugin on error (`'error'`) |
| `trino_catalog_registry` | `sync_error` | `text` | — | **Written** by the plugin on error (truncated to 1000 chars) |
| `trino_catalog_registry` | `catalog_version` | `text` | — | **Written** on `CREATE CATALOG`; nullable, not read at boot |
| `trino_catalog_registry` | `updated_by` | `text` | — | **Written**; identifies the role that wrote the row |

**Additional columns are free.** The plugin ignores any column it does not
explicitly select. Add indexes, audit columns, or whatever your backend needs —
the plugin will not see them.

### `updated_by` values

The `CHECK` constraint accepts `IN ('baleia', 'trino')`. These identify the
**role** that wrote the row, not a specific product:

| Value | Meaning |
|---|---|
| `'baleia'` | The control plane wrote or updated the row |
| `'trino'` | The coordinator wrote back (UPSERT on DDL, soft delete, error mark) |

If a backend needs to distinguish *which* control plane wrote the row, that is
a new nullable column — the plugin ignores what it does not know.

### N5 — provenance of `@baleia-secret[...]` (verified)

Airlift resolves its own `${...}` secret patterns **only** in
`etc/catalog-store.properties` — `CatalogStoreManager.setConfiguredCatalogStore` passes
the file through `secretsResolver.getResolvedConfiguration(...)` before invoking the
factory. The `CREATE CATALOG` path does **not** route through any secrets resolver:
`CreateCatalogTask.execute` → `MetadataManager.createCatalog` → `CatalogManager.createCatalog`
→ `CoordinatorDynamicCatalogManager.createCatalog` → `catalogStore.createCatalogProperties(...)`,
all transparent pass-through. So a `@baleia-secret[...]` placeholder in a `CREATE CATALOG`
statement reaches our `SecretResolver` unmutilated. The placeholder syntax is safe.

## Build

Requires Java 25+. Tested with `25.0.3-tem`; `air.java.version=25.0.1` in Trino's POM is the minimum. Install with SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.3-tem
sdk install maven
```

Build and package:

```bash
./mvnw clean package
ls target/baleia-catalog-store/   # plugin jar + runtime dependencies
```

> `./mvnw` is the Maven Wrapper (pinned to Maven 3.9.9) — use it everywhere;
> `mvn` also works if you have a compatible Maven installed.

## Tests and quality gates

```bash
./mvnw verify
```

`verify` runs, in order:

1. **Unit tests** (`mvn test`) — 42 tests.
2. **JaCoCo** — coverage report at `target/site/jacoco/index.html`.
3. **Checkstyle** — `config/checkstyle/checkstyle.xml` (imports, equals/hashCode,
   empty blocks, etc.).
4. **SpotBugs** — static analysis (`threshold=Medium`).
5. **Error Prone** — compiler checks, wired into javac via the Maven plugin.

Reports are also uploaded as artifacts on CI. Dependency vulnerability scanning is
opt-in because it downloads the NVD database on first run:

```bash
./mvnw verify -Pdependency-check
```

The entire `target/baleia-catalog-store/` directory goes to
`/usr/lib/trino/plugin/baleia-catalog-store/` inside the Trino container.

Verify the ServiceLoader entry without starting Trino:

```bash
unzip -p target/baleia-catalog-store/baleia-trino-catalog-store-0.2.0.jar \
    META-INF/services/io.trino.spi.Plugin
# expected:
# io.baleia.trino.catalogstore.BaleiaCatalogStorePlugin
```

## Tests

```bash
./mvnw test
```

Covers:

* `CatalogRowTest` — name validation, reserved words, immutability.
* `DatabaseParseFlatJsonTest` — flat JSON string→string parser.
* `DatabaseTest` — retry/backoff classification, timeout rounding, error truncation.
* `SecretResolverTest` — `@baleia-secret[<cat>:<key>]` substitution; the
  self-reference / malformed-placeholder rejection (D4b).
* `BaleiaCatalogStoreTest` — getCatalogs (resolution + skip/markError), create,
  add-or-replace and remove against a recording `Database` stub.
* `BaleiaCatalogStoreFactoryTest` — Guice wiring via `Bootstrap`.
* `BaleiaCatalogStoreConfigTest` — defaults and Jakarta validation ranges.
* `ComputeCatalogVersionTest` — golden SHA-256 (little-endian `putInt`,
  UTF-16LE `putUnencodedChars`) and the shared vector file
  `src/test/resources/catalog_version_vectors.json` — the same file is the
  contract with the Go backend's `catalogs.Version` implementation.

Fixed golden value (`name=tpch_teste, connector=tpch,
properties={"tpch.splits-per-node":"4"}`) plus four edge-case vectors live in
[`src/test/resources/catalog_version_vectors.json`](src/test/resources/catalog_version_vectors.json)
— that **file is the single source of truth**, consumed by both this test and
the Go backend's `catalogs.Version` golden test. Do not paste hashes into
prose (the README had a transcription typo that quietly diverged by two hex chars
and would have wasted hours chasing a non-existent Go bug). Update the JSON.

The vector file exercises four edge cases that bite a Go port:

* multibyte BMP (`\u2603` snowman — UTF-16 code unit count vs UTF-8 byte count)
* surrogate pair (`\uD83D\uDE80` rocket — `String.length()` counts 2 surrogates)
* empty properties
* an out-of-sorted-order map (forces `ImmutableSortedMap.copyOf` to reorder)

Add vectors to `catalog_version_vectors.json`; on first run, the test fails and
prints the actual hash to copy into the `expected_hash` field.

## Releases

Releases are automated with [Release Please](https://github.com/googleapis/release-please),
driven by the `Release` GitHub Actions workflow (`.github/workflows/release.yml`).
On every push to `main`, it:

1. Release Please reads [Conventional Commits](https://www.conventionalcommits.org/)
   and either creates/updates a **Release PR** (bumping `pom.xml` and writing
   `CHANGELOG.md`) or — if the Release PR was just merged — creates a **GitHub
   Release** with the new tag.
2. A `publish` job checks out the release tag and runs `./mvnw deploy` to
   publish the artifact to GitHub Packages. JitPack picks up the new tag
   automatically.

### Commit conventions

* `feat: ...` — new feature → minor bump.
* `fix: ...` — bug fix → patch bump.
* `feat!: ...` or a `BREAKING CHANGE:` footer → major bump.
* Everything else (`build`, `chore`, `docs`, `ci`, `test`, `refactor`,
  `perf`, `style`) — no new release.

### How it works

```
push to main → Release Please opens/updates Release PR
                  ↓
         merge Release PR → creates GitHub Release + tag vX.Y.Z
                                ↓
                       publish job → ./mvnw deploy to GitHub Packages
```

The Release PR is a normal PR — you can review the CHANGELOG entries and the
version bump before merging. No `npm`, no Node.js, no `.releaserc.json`.

**Do not edit `CHANGELOG.md` by hand** — Release Please regenerates it on
every release.

## Docker Compose

The `docker/` directory runs PostgreSQL 18 + Trino 482, mounting the plugin
directly from `target/` and bootstrapping the schema + role + seed via
`docker/initdb/` (Postgres entrypoint runs `*.sh` AND `*.sql` in a single
alphabetical pass on the first initdb of the data directory, so `01-` is
processed before `02-`).

```bash
cd docker
docker compose up -d
docker compose logs -f trino | grep -i "baleia\|catalog"
```

Mounted configuration:

* `docker/trino/config.properties` — `catalog.management=dynamic` and
  `catalog.store=baleia` (the key selecting the factory lives in
  `etc/config.properties`, **not** in `catalog-store.properties`).
* `docker/trino/catalog-store.properties` — properties passed to the factory:
  `baleia.jdbc-url`, `baleia.username`,
  `baleia.password=${ENV:BALEIA_TRINO_DB_PASSWORD}` (Airlift's own secrets
  resolver substitutes the env var, nothing committed),
  `baleia.cluster-name`, `baleia.connect-timeout=10s`, plus the tunable retry
  knobs `baleia.max-connect-attempts` (default 5), `baleia.initial-backoff`
  (default 2s) and `baleia.max-backoff` (default 30s).
* `etc/catalog/` is intentionally **not mounted** — must remain empty.

Important Postgres 18 detail:

* the `pgdata` named volume mounts at `/var/lib/postgresql` (the parent), **not** at
  `/var/lib/postgresql/data` (the old pre-18 path). The image's `VOLUME` directive
  moved and `PGDATA` is now `/var/lib/postgresql/18/docker`. Mounting at the old
  path silently drops your data on a recreate.
* `postgres` has a `pg_isready` healthcheck and `trino` waits on
  `service_healthy`. The plugin additionally retries with configurable backoff
  (default 5 attempts, 2s→30s, doubling) and fails the boot if it gives up — so
  a freshly-broken Postgres surfaces as a crash, not as a silent empty
  `SHOW CATALOGS`.

`docker/initdb/`:

* `01-schema.sql` — `trino_clusters` (UUID id),
  `trino_catalog_registry` with three `CHECK`s (name format + reserved names
  mirroring `CatalogRow`, `sync_status IN (...)`, `updated_by IN (...)`),
  and the `tpch_teste` seed row so the compose stack is self-contained.
* `02-role.sh` — creates/rotates `baleia_trino` from the `BALEIA_TRINO_DB_PASSWORD`
  env var (same env var the Trino container reads) and grants **only** `SELECT`
  on `trino_clusters` and `SELECT, INSERT, UPDATE, DELETE` on
  `trino_catalog_registry` — nothing else. The script runs with `set -euo
  pipefail` and `ON_ERROR_STOP=1`, so any SQL error aborts the postgres
  entrypoint. Because the postgres image runs `initdb/` only the first time
  the data dir is populated, an aborted first run means the volume is
  half-initialized and the role is missing until you wipe the volume:
  ```bash
  docker compose down -v   # crucial: -v removes the named pgdata volume
  docker compose up -d
  ```

> **Note — production vs dev.** `docker/initdb/` mirrors the production migration but is
> the dev bootstrap. Production deploys should apply the backend-owned
> migration, not these files. Keep the two in sync when the canonical
> migration changes.

## Acceptance (T1–T9)

`docker/acceptance.sh` walks T1-T9 against a running
compose stack. It exits non-zero on any failure and prints a summary table.

```bash
cd docker
docker compose up -d
./acceptance.sh
```

The author of
the original prompt did not run T1–T9 before delivery — that's how the
Postgres 18 volume mount, REVOKE syntax, and jvm.config mount issues
slipped through. Run it; do not skip it.

| #    | Test                                  | Status |
|------|---------------------------------------|--------|
| T1   | `SHOW CATALOGS` lists `tpch_teste`     | **PASS** |
| T2   | `SELECT count(*) FROM tpch_teste.tiny.nation` = 25 | **PASS** |
| T3   | T2 survives `docker compose restart trino` | **PASS** |
| T4   | `CREATE CATALOG tpch_dois ...` row lands with `updated_by='trino' sync_status='synced'` | **PASS** |
| T5   | `tpch_dois` survives a restart | **PASS** |
| T6   | `DROP CATALOG tpch_dois` → `enabled=false` | **PASS** |
| T6b  | `tpch_dois` does not return after restart | **PASS** |
| T7   | Row with non-string JSON value is skipped; boot survives; `sync_status='error'` | **PASS** |
| T8a  | `CREATE CATALOG` with placeholder resolves and catalog appears | **PASS** |
| T8b  | Registry stores the resolved value, not the placeholder | **PASS** |
| T8c  | Query log preserves the placeholder, never the resolved value | **PASS** |
| T9   | `INSERT ... catalog_name='system'` rejected by DB `CHECK` | **PASS** |

## Upgrading the Trino version

1. Run **Phase 0** (described in `docs/spi-482.md`): `git clone --depth 1
   --branch <new-tag>` somewhere and verify `CatalogStore` and
   `CatalogStoreFactory` are still in `core/trino-spi/.../io/trino/spi/catalog/`
   and `Plugin.getCatalogStoreFactories()` still exists.
2. Note `air.java.version` and `dep.airlift.version` from Trino's `pom.xml`.
3. Update in this repository's `pom.xml`: `<trino.version>`, `<java.release>`,
   `<airlift.version>` (and, if changed, `<jackson.version>`, `<units.version>`).
4. Update the image tag in `docker/docker-compose.yml`
   (`trinodb/trino:<new-tag>`).
5. Run `mvn clean package` — watch for:

   * `LinkageError` in Airlift classes → version mismatch.
   * `UnsupportedClassVersionError` → plugin compiled with a newer Java than
     the Trino container's JVM.
   * Signature changes in `CatalogStore`/`CatalogStoreFactory` → adjust
     `BaleiaCatalogStore` and `BaleiaStoredCatalog`.

6. Re-check the N5 finding: grep `CreateCatalogTask`, `MetadataManager.createCatalog`,
   and `CoordinatorDynamicCatalogManager.createCatalog` for any new
   `secretsResolver`/`getResolvedConfiguration` call. The current placeholder
   syntax is `@baleia-secret[catalog:key]` (chosen outside the `${...}` shape
   that bash, picocli, and Airlift all interpret). If a new collision appears,
   pick another non-`${...}` shape and update the regex in `SecretResolver.PLACEHOLDER`
   plus the Go DDL generator.
7. Update `docs/spi-<new-tag>.md` with actual signatures, comment in
   `docs/version-history.md` (create it if absent), and re-run `acceptance.sh`.

## Structure

```
baleia-trino-catalog-store/
├── pom.xml
├── README.md
├── docs/
│   └── spi-482.md                  # actual SPI signatures (Phase 0)
├── docker/
│   ├── docker-compose.yml
│   ├── acceptance.sh               # T1–T9 driver
│   ├── initdb/                     # Postgres entrypoint bootstrap
│   │   ├── 01-schema.sql
│   │   └── 02-role.sh
│   └── trino/
│       ├── config.properties
│       └── catalog-store.properties       # (jvm.config / node.properties come from the image)
└── src/
    ├── main/
    │   ├── java/io/baleia/trino/catalogstore/
    │   │   ├── BaleiaCatalogStorePlugin.java      # Plugin.getCatalogStoreFactories()
    │   │   ├── BaleiaCatalogStoreFactory.java     # getName() = "baleia"
    │   │   ├── BaleiaCatalogStoreConfig.java       # @Config("baleia.*"), connect-timeout as Duration
    │   │   ├── BaleiaCatalogStore.java             # CatalogStore impl; eager resolution in getCatalogs()
    │   │   ├── BaleiaStoredCatalog.java            # StoredCatalog impl (pre-resolved)
    │   │   ├── CatalogRow.java                     # validated record
    │   │   ├── Database.java                       # JDBC via PGSimpleDataSource; retry + fail-fast
    │   │   └── SecretResolver.java                 # @baleia-secret[...]; rejects self-reference (D4b)
    │   └── resources/META-INF/services/
    │       └── io.trino.spi.Plugin                 # ServiceLoader
    └── test/
        ├── java/io/baleia/trino/catalogstore/
        │   ├── CatalogRowTest.java
        │   ├── ComputeCatalogVersionTest.java        # golden SHA-256 + vectors from JSON
        │   ├── DatabaseParseFlatJsonTest.java
        │   └── SecretResolverTest.java
        └── resources/
            └── catalog_version_vectors.json        # shared Java/Go golden testdata
```

## Design decisions

### No connection pool

The plugin does **not** use a connection pool. `PGSimpleDataSource` is used
directly, one connection per operation. Rationale:

- The plugin is **not** on the query path. A user running `SELECT` in the editor
  generates zero plugin connections — the path is Baleia → Trino → Polaris/S3.
- Plugin I/O is limited to boot (`getCatalogs()`, one connection regardless of
  catalog count), DDL (`upsert`/`softDelete`, administrative, rare), and
  `loadProperties()` during DDL to resolve secrets (memoized per source catalog
  by SecretResolver).
- A pool adds: idle sockets 24/7 against Postgres in a long-lived process; an
  extra jar in the plugin classpath (more `LinkageError` surface); and — the
  decisive factor — the SPI `CatalogStore` has no `close()` method or shutdown
  hook in Trino 482. A pool created in `CatalogStoreFactory.create()` would
  never be cleaned up.

**Revisit trigger:** if Trino issue [#26760](https://github.com/trinodb/trino/issues/26760)
(refresh of external catalog store) is merged and adopted, the plugin would
poll periodically. At that point a pool becomes appropriate.

### No hyphen normalization in connector_name

Rejection at the boundary, three layers deep: DB `CHECK` constraint,
`CatalogRow` constructor, and Trino SPI's own `ConnectorName(String)`. No
compatibility layer — see `docs/spi-482.md` §ConnectorName.

### Don't repackage Trino

The plugin ships as a tarball for side-loading into any existing Trino
installation. See "Installing in an existing Trino" section.

## Security notes

* `docker/trino/catalog-store.properties` never commits a real password; it uses
  `baleia.password=${ENV:BALEIA_TRINO_DB_PASSWORD}` and the compose stack reads
  the env var at runtime. The actual value `change_before_deploy` for the dev
  role is set in `docker/initdb/02-role.sh` from the same env var;
  `change_before_deploy` is the dev placeholder to symlink an enablement secret.
* The `baleia_trino` PostgreSQL user has grants **only** on
  `trino_catalog_registry` (and read on `trino_clusters` for the JOIN). No
  other tables — this ensures that even if Trino is compromised, the plugin
  cannot read users/queries/other metadata.
* `SecretResolver` exists so the `CREATE CATALOG` text — which Trino logs and
  exposes in `system.runtime.queries` — never contains real credentials.
  The persisted value in the DB carries the resolved secret; the log stays as
  a placeholder. The D4b rule rejects any value that still looks like a
  `@baleia-secret[...]` after resolution, eliminating the circular-reference
  and bad-regex escape hatches.

### Secret resolution schemes

`SecretResolver` supports three origin schemes in `@baleia-secret[<origin>:<key>]`:

| Scheme | Syntax | Resolution | Rotation |
|---|---|---|---|
| `catalog:` | `@baleia-secret[<catalog>:<key>]` | Another row in `trino_catalog_registry` | Via `CREATE CATALOG` DDL |
| `file:` | `@baleia-secret[file:<name>]` | File under `baleia.secret-file-base-dir` | kubelet updates Secret mount; next catalog reload picks up |
| `env:` | `@baleia-secret[env:<VAR>]` | Process environment variable | Requires Trino restart |

**`file:` (recommended for Kubernetes):**
The backend writes only the reference `@baleia-secret[file:db-password.txt]`
to the database. The actual credential lives in a Kubernetes Secret mounted as a
file. The Postgres database never contains the credential in cleartext.

```yaml
# Kubernetes Pod spec
volumes:
  - name: db-secrets
    secret:
      secretName: trino-db-secrets
containers:
  - name: trino
    volumeMounts:
      - name: db-secrets
        mountPath: /etc/trino/secrets
        readOnly: true
```

```properties
# catalog-store.properties
baleia.secret-file-base-dir=/etc/trino/secrets
```

Restrictions on `file:`:
* `baleia.secret-file-base-dir` must be non-empty (empty disables the scheme).
* File names may only contain `[A-Za-z0-9._-]` — no `/`, no `..`, no absolute path.
* Symlinks that resolve outside the base directory are rejected.
* Maximum file size: 64 KiB.
* Files are re-read on every `resolve()` call — no Trino restart needed for rotation.

**`env:` (for non-Kubernetes deployments):**
Reads an environment variable from the Trino process. The variable must be set
before the JVM starts; rotating the value requires restarting the coordinator.
This is documented as a limitation — use `file:` in Kubernetes when possible.

**Reserved names:** `env` and `file` are reserved as catalog names (they cannot
be used as `catalog_name` in `trino_catalog_registry`) to avoid ambiguity with
the scheme syntax.

