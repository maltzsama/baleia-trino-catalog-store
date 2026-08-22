package io.baleia.trino.catalogstore;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.spi.TrinoException;
import io.trino.spi.catalog.CatalogName;
import io.trino.spi.catalog.CatalogProperties;
import io.trino.spi.catalog.CatalogStore;
import io.trino.spi.connector.CatalogVersion;
import io.trino.spi.connector.ConnectorName;

import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * Trino {@link CatalogStore} implementation backed by Baleia's PostgreSQL.
 *
 * <p>This is the core of the plugin. It implements the four SPI methods that
 * Trino calls to manage catalogs:
 * <ul>
 *   <li>{@link #getCatalogs()} — boot path: loads all enabled rows, eagerly
 *       resolves secrets, and returns {@link StoredCatalog} instances.</li>
 *   <li>{@link #createCatalogProperties} — DDL path: resolves secrets in the
 *       user-supplied properties and computes a deterministic SHA-256 version.</li>
 *   <li>{@link #addOrReplaceCatalog} — persists the catalog row via UPSERT.</li>
 *   <li>{@link #removeCatalog} — soft-deletes the catalog (sets {@code enabled=false}).</li>
 * </ul>
 *
 * <p>The plugin does <b>not</b> poll. Runtime catalog propagation is the
 * backend's responsibility via {@code CREATE CATALOG} DDL.
 *
 * <p>Error handling follows a two-tier model:
 * <ul>
 *   <li>Connection-level failures ({@link TrinoException}) fail the boot.</li>
 *   <li>Row-level failures (bad JSON, dangling secret reference) log, mark
 *       {@code sync_status='error'}, and skip the row.</li>
 * </ul>
 *
 * @see Database
 * @see SecretResolver
 * @see BaleiaStoredCatalog
 */
public class BaleiaCatalogStore
        implements CatalogStore
{
    private static final Logger log = Logger.get(BaleiaCatalogStore.class);

    private final Database database;
    private final SecretResolver secretResolver;

    @Inject
    public BaleiaCatalogStore(Database database, SecretResolver secretResolver)
    {
        this.database = database;
        this.secretResolver = secretResolver;
    }

    /**
     * Loads all enabled catalogs from the database, resolves secrets, and
     * returns them as {@link StoredCatalog} instances.
     *
     * <p>Called once at coordinator boot. Each row is processed independently:
     * a bad row is logged, marked {@code sync_status='error'}, and skipped.
     * A total database failure retries with exponential backoff, then fails the boot.
     *
     * @return an unmodifiable collection of catalogs ready for Trino to load
     */
    @Override
    public Collection<StoredCatalog> getCatalogs()
    {
        List<CatalogRow> rows = database.loadAll();
        ImmutableList.Builder<StoredCatalog> out = ImmutableList.builder();
        for (CatalogRow row : rows) {
            try {
                // D4: eager resolution inside the per-row try/catch.
                // DB-layer failures surface as TrinoException and fail-boot (considered
                // total connection loss, see Database.retrying).
                // Bad-row failures (illegal name, bad JSON, dangling secret reference)
                // log + markError and skip the row.
                ConnectorName connectorName = new ConnectorName(row.connectorName());
                Map<String, String> resolved = secretResolver.resolve(row.properties());
                out.add(new BaleiaStoredCatalog(row, connectorName, resolved));
            }
            catch (TrinoException e) {
                // Total DB failure: fail boot.
                throw e;
            }
            catch (RuntimeException e) {
                log.warn(e, "Catalog '%s' skipped: %s", row.catalogName(), e.getMessage());
                database.markError(row.catalogName(), e.getMessage());
            }
        }
        return out.build();
    }

    /**
     * Resolves secrets in the user-supplied properties and computes a
     * deterministic {@link CatalogVersion} (SHA-256).
     *
     * <p>Called by Trino during {@code CREATE CATALOG}. The returned
     * {@link CatalogProperties} carries the resolved values and the computed
     * version — the caller will later pass it to {@link #addOrReplaceCatalog}.
     *
     * @param catalogName  the catalog name from the DDL statement
     * @param connectorName the connector type (e.g. "iceberg", "tpch")
     * @param properties   raw properties from the DDL, may contain placeholders
     * @return resolved properties with a deterministic version hash
     * @throws IllegalStateException if a placeholder cannot be resolved
     */
    @Override
    public CatalogProperties createCatalogProperties(
            CatalogName catalogName, ConnectorName connectorName, Map<String, String> properties)
    {
        Map<String, String> resolved = secretResolver.resolve(properties);
        CatalogVersion version = computeCatalogVersion(catalogName, connectorName, resolved);
        return new CatalogProperties(catalogName, version, connectorName, ImmutableMap.copyOf(resolved));
    }

    /**
     * Persists a catalog row via UPSERT. Sets {@code sync_status='synced'}
     * and {@code updated_by='trino'}.
     *
     * @param catalogProperties the resolved catalog to persist
     */
    @Override
    public void addOrReplaceCatalog(CatalogProperties catalogProperties)
    {
        String name = catalogProperties.name().toString();
        String connector = catalogProperties.connectorName().toString();
        Map<String, String> props = catalogProperties.properties();
        String version = catalogProperties.version().toString();

        CatalogRow row = new CatalogRow(name, connector, props);
        database.upsert(row, version);
        log.info("Catalog '%s' persisted in Baleia (version=%s)", name, version);
    }

    /**
     * Soft-deletes a catalog by setting {@code enabled=false}. The row
     * remains in the database for audit purposes.
     *
     * @param catalogName the catalog to disable
     */
    @Override
    public void removeCatalog(CatalogName catalogName)
    {
        String name = catalogName.toString();
        database.softDelete(name);
        log.info("Catalog '%s' disabled in Baleia", name);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exact replica of the FileCatalogStore algorithm. DO NOT CHANGE.
    // The Go backend has a copy of this and both must match byte-for-byte.
    // putInt  -> little-endian (Guava)
    // putUnencodedChars -> UTF-16LE (Guava)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes a deterministic SHA-256 catalog version.
     *
     * <p>This is an exact replica of Trino's {@code FileCatalogStore.computeCatalogVersion}.
     * The algorithm must match byte-for-byte across Java and any backend
     * (Go, Rails, etc.) that computes catalog versions.
     *
     * <p>Order of hashing:
     * <ol>
     *   <li>Fixed prefix {@code "catalog-hash"}</li>
     *   <li>Length-prefixed catalog name (UTF-16LE)</li>
     *   <li>Length-prefixed connector name (UTF-16LE)</li>
     *   <li>Property count (little-endian int)</li>
     *   <li>Each key-value pair, length-prefixed, in sorted key order</li>
     * </ol>
     *
     * @param catalogName   the catalog name
     * @param connectorName the connector type
     * @param properties    the resolved properties map
     * @return a hex-encoded SHA-256 hash as {@link CatalogVersion}
     */
    static CatalogVersion computeCatalogVersion(
            CatalogName catalogName, ConnectorName connectorName, Map<String, String> properties)
    {
        Hasher hasher = Hashing.sha256().newHasher();
        hasher.putUnencodedChars("catalog-hash");
        hashLengthPrefixedString(hasher, catalogName.toString());
        hashLengthPrefixedString(hasher, connectorName.toString());
        hasher.putInt(properties.size());
        ImmutableSortedMap.copyOf(properties).forEach((key, value) -> {
            hashLengthPrefixedString(hasher, key);
            hashLengthPrefixedString(hasher, value);
        });
        return new CatalogVersion(hasher.hash().toString());
    }

    private static void hashLengthPrefixedString(Hasher hasher, String value)
    {
        hasher.putInt(value.length());
        hasher.putUnencodedChars(value);
    }
}