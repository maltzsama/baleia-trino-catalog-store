# SPI Compatibility

This plugin compiles against the **lowest** supported Trino version and runs on
any version in the support window. The artifact published is always the floor
build.

## Supported versions

| Trino | Compiles | Acceptance | Artifact | Status |
|-------|----------|------------|----------|--------|
| 482   | yes      | yes        | floor    | supported |
| 483   | yes      | yes        | same     | supported |

**Policy:** support the current and previous Trino releases. When a new version
ships (e.g. 484), the floor moves up (to 483), the oldest version drops out, and
the line disappears from this table in the same release.

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

The DDL call chain in both 482 and 483:

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

**Verified by:** compilation against both SPI versions and acceptance tests
passing on both Trino images. If a future version introduces a secrets resolver
on the DDL path, the `@baleia-secret[...]` syntax (outside `${...}`) still
survives — but the behavior would change and must be re-evaluated.

## Upgrade procedure

When a new Trino version ships:

1. Compile against the new SPI: `./mvnw verify -Dtrino.version=<new>`
2. Run acceptance against the new image: `TRINO_IMAGE=trinodb/trino:<new> ./docker/acceptance.sh`
3. If both pass, add the version to the matrix and this table, remove the
   oldest, update the floor in CI, and release.
4. If compilation fails, the SPI surface changed — evaluate whether T5
   (multi-module) is needed.
5. If acceptance fails at runtime (`NoSuchMethodError`, `AbstractMethodError`),
   the binary compatibility broke — T5 is mandatory.
6. Re-verify N5 against the new source: grep `CreateCatalogTask`,
   `MetadataManager.createCatalog`, and `CoordinatorDynamicCatalogManager` for
   any new `secretsResolver` / `getResolvedConfiguration` reference.
