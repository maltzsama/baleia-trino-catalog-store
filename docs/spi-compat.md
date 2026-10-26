# SPI Compatibility

This plugin compiles against the supported Trino versions and runs on them. Each
release publishes one artifact per supported version (currently two).

## Supported versions

| Trino | Compiles | Acceptance | Artifact | Status |
|-------|----------|------------|----------|--------|
| 483   | yes      | yes        | `+trino483` | supported — legacy artifact Baleia pins |
| 484   | yes      | yes        | `+trino484` | supported — default build |

Trino 482 is no longer supported (#79): the last release with a `+trino482`
artifact is the one before that change. The SPI surface the plugin uses is
identical in 482 and 483, so the code did not need to change.

Trino 484 adds `checkCanSetMaterializedViewComment` to `SystemAccessControl`.
The guard implements it (without `@Override`, so the same source still compiles
against 483) and requires a writable session, like every other comment check.

**Policy:** a version leaves this table in the same release that drops it from CI,
the release tarballs and `docker/`.

## SPI surface used

8 types, ~7 methods:

| Type | Package | Usage |
|------|---------|-------|
| `Plugin` | `io.trino.spi` | `getCatalogStoreFactories()` |
| `CatalogStoreFactory` | `io.trino.spi.catalog` | `getName()`, `create(Map)` |
| `CatalogStore` | `io.trino.spi.catalog` | `getCatalogs()`, `createCatalogProperties(...)`, `addOrReplaceCatalog(...)`, `removeCatalog(...)` |
| `CatalogStore.StoredCatalog` | `io.trino.spi.catalog` | `name()`, `loadProperties()` |
| `CatalogName` | `io.trino.spi.catalog` | identity |
| `CatalogProperties` | `io.trino.spi.catalog` | value object |
| `ConnectorName` | `io.trino.spi.connector` | identity |
| `CatalogVersion` | `io.trino.spi.connector` | value object |
| `TrinoException` | `io.trino.spi` | error signaling |

All dependencies are `provided` scope — the coordinator supplies them at runtime.
Packaging them causes `LinkageError`.

## N5 — `@baleia-secret[...]` placeholder safety

The DDL call chain in 483 (unchanged from 482):

```
CreateCatalogTask.execute
  → MetadataManager.createCatalog
    → CatalogManager.createCatalog
      → CoordinatorDynamicCatalogManager.createCatalog
        → catalogStore.createCatalogProperties(...)
        → catalogStore.addOrReplaceCatalog(...)
```

No `secretsResolver.getResolvedConfiguration` appears on this path. A
`@baleia-secret[...]` placeholder in a `CREATE CATALOG` statement reaches our
`SecretResolver` unmutilated.

**Verified by:** compilation against the SPI and acceptance tests passing on the
Trino image. If a future version introduces a secrets resolver
on the DDL path, the `@baleia-secret[...]` syntax (outside `${...}`) still
survives — but the behavior would change and must be re-evaluated.

## Upgrade procedure

When a new Trino version ships:

1. Compile against the new SPI: `./mvnw verify -Dtrino.version=<new>`
2. Run acceptance against the new image: `TRINO_IMAGE=trinodb/trino:<new> ./docker/acceptance.sh`
3. If both pass, add the version to the matrix (CI and release) and this table,
   decide whether to keep the previous one, and release.
4. If compilation fails, the SPI surface changed — evaluate whether T5
   (multi-module) is needed.
5. If acceptance fails at runtime (`NoSuchMethodError`, `AbstractMethodError`),
   the binary compatibility broke — T5 is mandatory.
6. Re-verify N5 against the new source: grep `CreateCatalogTask`,
   `MetadataManager.createCatalog`, and `CoordinatorDynamicCatalogManager` for
   any new `secretsResolver` / `getResolvedConfiguration` reference.
