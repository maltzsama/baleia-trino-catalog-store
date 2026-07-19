# baleia-trino-catalog-store

A Trino plugin that implements `CatalogStore` and makes the coordinator read catalogs
directly from the `trino_catalog_registry` table in Baleia's PostgreSQL — eliminating
static `.properties` files in `etc/catalog/`.

* **Target Trino version:** 482 (pluggable SPI)
* **Java:** 25 (`air.java.version=25.0.1`)
* **Airlift:** 435 (same as `dep.airlift.version` in Trino)
* **Plugin registration:** ServiceLoader in
  `src/main/resources/META-INF/services/io.trino.spi.Plugin`

The actual SPI 482 signatures are documented in
[`docs/spi-482.md`](docs/spi-482.md) — consult/update it whenever changing Trino version.

## What the plugin does

| When | Trino calls | Plugin does |
|---|---|---|
| Coordinator boot | `getCatalogs()` | `SELECT` enabled rows; per-row: parse, validate name, **eagerly resolve `${baleia-secret:...}`**; bad rows mark `sync_status='error'` and are skipped; total DB failure retries with backoff then fails the boot |
| `CREATE CATALOG` | `createCatalogProperties()` then `addOrReplaceCatalog()` | Resolves `${baleia-secret:...}` (fails the DDL on unresolved/circular reference), `UPSERT`s the row carrying the computed `catalog_version` |
| `DROP CATALOG` | `removeCatalog()` | `UPDATE ... SET enabled = false` (soft delete) |

The plugin does **not** poll — runtime propagation is the Go backend's responsibility
via `CREATE CATALOG`. It does **not** build connector properties — all knowledge about
Polaris, Iceberg, REST catalog lives in Go; this plugin only transports.

### N5 — provenance of `${baleia-secret:...}` (verified)

Airlift resolves its own `${...}` secret patterns **only** in
`etc/catalog-store.properties` — `CatalogStoreManager.setConfiguredCatalogStore` passes
the file through `secretsResolver.getResolvedConfiguration(...)` before invoking the
factory. The `CREATE CATALOG` path does **not** route through any secrets resolver:
`CreateCatalogTask.execute` → `MetadataManager.createCatalog` → `CatalogManager.createCatalog`
→ `CoordinatorDynamicCatalogManager.createCatalog` → `catalogStore.createCatalogProperties(...)`,
all transparent pass-through. So a `${baleia-secret:...}` placeholder in a `CREATE CATALOG`
statement reaches our `SecretResolver` unmutilated. The placeholder syntax is safe.

## Build

Requires Java 25 (same `<air.java.version>` as Trino 482). Install with SDKMAN:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.3-tem
sdk install maven
```

Build and package:

```bash
mvn clean package
ls target/baleia-catalog-store/   # plugin jar + runtime dependencies
```

The entire `target/baleia-catalog-store/` directory goes to
`/usr/lib/trino/plugin/baleia-catalog-store/` inside the Trino container.

Verify the ServiceLoader entry without starting Trino:

```bash
unzip -p target/baleia-catalog-store/baleia-trino-catalog-store-0.1.0-SNAPSHOT.jar \
    META-INF/services/io.trino.spi.Plugin
# expected:
# io.baleia.trino.catalogstore.BaleiaCatalogStorePlugin
```

## Tests

```bash
mvn test
```

Covers:

* `CatalogRowTest` — name validation, reserved words, immutability.
* `DatabaseParseFlatJsonTest` — flat JSON string→string parser.
* `SecretResolverTest` — `${baleia-secret:<cat>:<key>}` substitution; the
  self-reference / malformed-placeholder rejection (D4b).
* `ComputeCatalogVersionTest` — golden SHA-256 (little-endian `putInt`,
  UTF-16LE `putUnencodedChars`) and the shared vector file
  `src/test/resources/catalog_version_vectors.json` — the same file is the
  contract with the Go backend's `catalogs.Version` implementation.

Fixed golden value (`name=tpch_teste, connector=tpch,
properties={"tpch.splits-per-node":"4"}`):

```
c4927418129a39beae1c13b2c9f02adba0c554e8f7d450d170d323b1877c3b82
```

(if this changes unintentionally, it's a bug — update the Go copy too).

The vector file also exercises the two failure modes that bite a Go port:

* multibyte BMP (`\u2603` snowman — UTF-16 code unit count vs UTF-8 byte count)
* surrogate pair (`\uD83D\uDE80` rocket — `String.length()` counts 2 surrogates)
* empty properties
* an out-of-sorted-order map (forces `ImmutableSortedMap.copyOf` to reorder)

Add vectors to `catalog_version_vectors.json`; on first run, the test fails and
prints the actual hash to copy into the `expected_hash` field.

## Docker Compose

The `docker/` directory runs PostgreSQL 18 + Trino 482, mounting the plugin
directly from `target/` and bootstrapping the schema + role + seed via
`docker/initdb/` (Postgres entrypoint auto-runs `*.sh` then `*.sql` there).

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
  `baleia.cluster-name`, `baleia.connect-timeout=10s`.
* `etc/catalog/` is intentionally **not mounted** — must remain empty.

Important Postgres 18 detail (CR-015 N1):

* the `pgdata` named volume mounts at `/var/lib/postgresql` (the parent), **not** at
  `/var/lib/postgresql/data` (the old pre-18 path). The image's `VOLUME` directive
  moved and `PGDATA` is now `/var/lib/postgresql/18/docker`. Mounting at the old
  path silently drops your data on a recreate.
* `postgres` has a `pg_isready` healthcheck and `trino` waits on
  `service_healthy`. The plugin additionally retries with backoff (5 attempts,
  2s→30s) and fails the boot if it gives up — so a freshly-broken Postgres
  surfaces as a crash, not as a silent empty `SHOW CATALOGS`.

`docker/initdb/`:

* `01-schema.sql` — `trino_clusters`, `trino_catalog_registry` (+ a `CHECK` on
  name format and reserved names, mirroring `CatalogRow`), and the `tpch_teste`
  seed row so the compose stack is self-contained.
* `02-role.sh` — creates/rotates `baleia_trino` from the `BALEIA_TRINO_PASSWORD`
  env var and grants **only** `SELECT` on `trino_clusters` and
  `SELECT, INSERT, UPDATE, DELETE` on `trino_catalog_registry` — nothing else.

> **Note — production vs dev.** `docker/initdb/` mirrors CR-014 §4.1 but is
> the dev bootstrap. Production deploys should apply the backend-owned
> migration, not these files. Keep the two in sync when the canonical
> migration changes.

## Acceptance (T1–T9)

`docker/acceptance.sh` walks the CR-015 rev.2 §6 checklist against a running
compose stack. It exits non-zero on any failure and prints a summary table.

```bash
cd docker
docker compose up -d
./acceptance.sh
```

It is the **closing item** of CR-015 rev.2. The author of the original prompt did
not run T1–T9 before delivery — that's how N1 (the Postgres 18 volume mount)
slipped through. Run it; do not skip it.

| #   | Test                                  | Status |
|-----|---------------------------------------|--------|
| T1  | `SHOW CATALOGS` lists `tpch_teste`     | _record after run_ |
| T2  | `SELECT count(*) FROM tpch_teste.tiny.nation` = 25 | _record after run_ |
| T3  | T2 survives `docker compose restart trino` | _record after run_ |
| T4  | `CREATE CATALOG tpch_dois ...` row lands with `updated_by='trino' sync_status='synced'` | _record after run_ |
| T5  | `tpch_dois` survives a restart | _record after run_ |
| T6  | `DROP CATALOG tpch_dois` → `enabled=false`; not back after restart | _record after run_ |
| T7  | Row with non-string JSON value is skipped; boot survives; `sync_status='error'` | _record after run_ |
| T8  | `CREATE CATALOG` text in `system.runtime.queries` keeps the placeholder, never the resolved value | _record after run_ |
| T9  | `INSERT ... catalog_name='system'` rejected by DB `CHECK` | _record after run_ |

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
   `secretsResolver`/`getResolvedConfiguration` call. If one appears, change the
   placeholder syntax in `SecretResolver` (`${baleia-secret:...}` → something
   outside Airlift's `${provider:key}` shape, e.g. `@baleia-secret[...]`).
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
│   ├── initdb/                     # Postgres entrypoint bootstrap (CR-014 §4.1 mirror)
│   │   ├── 01-schema.sql
│   │   └── 02-role.sh
│   └── trino/
│       ├── config.properties
│       ├── node.properties
│       ├── jvm.config
│       └── catalog-store.properties
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
    │   │   └── SecretResolver.java                 # ${baleia-secret:...}; rejects self-reference (D4b)
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

## Security notes

* `docker/trino/catalog-store.properties` never commits a real password; it uses
  `baleia.password=${ENV:BALEIA_TRINO_DB_PASSWORD}` and the compose stack reads
  the env var at runtime. The actual value `change_before_deploy` for the dev
  role is set in `docker-initdb/02-role.sh` from the same env var;
  `change_before_deploy` is the dev placeholder to symlink an enablement secret.
* The `baleia_trino` PostgreSQL user has grants **only** on
  `trino_catalog_registry` (and read on `trino_clusters` for the JOIN). No
  other tables — this ensures that even if Trino is compromised, the plugin
  cannot read users/queries/other metadata.
* `SecretResolver` exists so the `CREATE CATALOG` text — which Trino logs and
  exposes in `system.runtime.queries` — never contains real credentials.
  The persisted value in the DB carries the resolved secret; the log stays as
  a placeholder. The D4b rule rejects any value that still looks like a
  `${baleia-secret:...}` after resolution, eliminating the circular-reference
  and bad-regex escape hatches.

## CR history

* **CR-014 rev.3** — original implementation plan (Trino target 481, the
  assumption that `CatalogStore` was already a SPI plug point turned out to
  require a runtime version bump — see `docs/spi-482.md`). Section 7 of the
  CR asserted `catalog-store.name=baleia` in `etc/catalog-store.properties`;
  the SPI inspection showed the actual selection key is `catalog.store` in
  `etc/config.properties`. Both corrected in this repository.
* **CR-015 rev.2** — review of the as-merged code. Closed defects: D2
  (`DriverManager` → `PGSimpleDataSource`), D3 (retry + fail-fast on total
  connection failure), D4 (eager secret resolution + D4b self-reference
  rejection), D6 (`SELECT_ONE` filters `enabled`), D7 (`normalize()` is dead
  — removed). Closed compose issues: N1 (Postgres 18 volume mount),
  N2 (healthcheck + `service_healthy`), N3 (`docker/initdb/` bootstrap),
  N6 (`${ENV:...}` for the password). Verified N5 (CREATE CATALOG path does
  not route through Airlift's secrets resolver). Added M2 (cluster validation
  on first connect), M1 (configurable `connect-timeout` as `Duration`),
  M4 (`sync_error` truncated at 1000 chars), M3 (shared vector file +
  multibyte/empty test cases). The remaining item, T1–T9 against a
  running stack, is the closure (`docker/acceptance.sh`).