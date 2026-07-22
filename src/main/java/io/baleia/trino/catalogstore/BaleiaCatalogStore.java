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

    @Override
    public CatalogProperties createCatalogProperties(
            CatalogName catalogName, ConnectorName connectorName, Map<String, String> properties)
    {
        Map<String, String> resolved = secretResolver.resolve(properties);
        CatalogVersion version = computeCatalogVersion(catalogName, connectorName, resolved);
        return new CatalogProperties(catalogName, version, connectorName, ImmutableMap.copyOf(resolved));
    }

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