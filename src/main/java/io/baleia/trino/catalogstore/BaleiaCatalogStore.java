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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.trino.spi.StandardErrorCode.CATALOG_STORE_ERROR;
import static java.lang.String.format;


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

    // CREATE CATALOG calls createCatalogProperties() and then addOrReplaceCatalog() back to
    // back, on one thread, under CoordinatorDynamicCatalogManager's catalogsUpdateLock. The
    // second call only receives the resolved properties, so the original text (with the
    // @baleia-secret placeholders) is kept here, by catalog name, to be persisted instead.
    private final Map<String, PendingCatalog> pending = new ConcurrentHashMap<>();

    // A CREATE that fails after createCatalogProperties() but before addOrReplaceCatalog()
    // leaves an entry behind. Entries older than this are swept on every put, so a failing
    // DDL path cannot leak memory.
    static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private final Clock clock;

    private record PendingCatalog(String version, Map<String, String> rawProperties, Instant createdAt) {}

    @Inject
    public BaleiaCatalogStore(Database database, SecretResolver secretResolver)
    {
        this(database, secretResolver, Clock.systemUTC());
    }

    BaleiaCatalogStore(Database database, SecretResolver secretResolver, Clock clock)
    {
        this.database = database;
        this.secretResolver = secretResolver;
        this.clock = clock;
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
        ImmutableList.Builder<String> loaded = ImmutableList.builder();
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
                loaded.add(row.catalogName());
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
        // A row that failed on an earlier boot and loads fine now must not stay 'error'.
        database.markSynced(loaded.build());
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
        Instant now = clock.instant();
        evictPending(now);
        pending.put(catalogName.toString(), new PendingCatalog(version.toString(), persistedProperties(catalogName.toString(), properties, resolved), now));
        return new CatalogProperties(catalogName, version, connectorName, ImmutableMap.copyOf(resolved));
    }

    /** Drops pending entries a CREATE left behind for longer than {@link #PENDING_TTL}. */
    private void evictPending(Instant now)
    {
        Instant cutoff = now.minus(PENDING_TTL);
        pending.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    /**
     * What the row keeps: the DDL's text, placeholders intact, except a
     * reference to the catalog itself ({@code @baleia-secret[<this catalog>:<key>]}).
     * That value lives in this very row, which the UPSERT replaces: persisting
     * the reference would leave the row pointing at itself, and the next boot
     * would skip the catalog as a circular reference. So the row keeps the value
     * it already held (the resolved one). {@code env:}, {@code file:} and other
     * catalogs' references stay references.
     */
    static Map<String, String> persistedProperties(String catalogName, Map<String, String> raw, Map<String, String> resolved)
    {
        ImmutableMap.Builder<String, String> out = ImmutableMap.builder();
        raw.forEach((key, value) -> out.put(key, SecretResolver.refersToCatalog(catalogName, value) ? resolved.get(key) : value));
        return out.buildOrThrow();
    }

    /**
     * Persists a catalog row via UPSERT. Sets {@code sync_status='synced'}
     * and {@code updated_by='trino'}.
     *
     * <p>The row holds the properties as the user wrote them, with the
     * {@code @baleia-secret[...]} placeholders intact: resolved values never reach the
     * database. They are resolved again on every boot by {@link #getCatalogs()}. The one
     * exception is a reference to the catalog itself, which keeps the value the row
     * already held (see {@link #persistedProperties}).
     *
     * @param catalogProperties the catalog returned by {@link #createCatalogProperties}
     * @throws TrinoException if the catalog was not created through this store
     */
    @Override
    public void addOrReplaceCatalog(CatalogProperties catalogProperties)
    {
        String name = catalogProperties.name().toString();
        String connector = catalogProperties.connectorName().toString();
        String version = catalogProperties.version().toString();

        // Never fall back to catalogProperties.properties(): those are the resolved values.
        // Only consume the entry that matches: a stale version must not discard a newer one.
        PendingCatalog original = pending.get(name);
        if (original == null || !original.version().equals(version) || !pending.remove(name, original)) {
            throw new TrinoException(CATALOG_STORE_ERROR,
                    format("Refusing to persist catalog '%s': it was not created through createCatalogProperties, "
                            + "so the original @baleia-secret placeholders are unknown and the resolved values "
                            + "would be written to the database", name));
        }

        CatalogRow row = new CatalogRow(name, connector, original.rawProperties());
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