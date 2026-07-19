package io.baleia.trino.catalogstore;

import com.google.common.collect.ImmutableMap;
import io.trino.spi.catalog.CatalogName;
import io.trino.spi.catalog.CatalogProperties;
import io.trino.spi.catalog.CatalogStore.StoredCatalog;
import io.trino.spi.connector.ConnectorName;

import java.util.Map;

/**
 * A catalog loaded from the database. Properties are already resolved
 * (secrets substituted) at construction time inside {@link BaleiaCatalogStore#getCatalogs()},
 * so {@link #loadProperties()} is pure packaging — no DB call here.
 *
 * <p>Trino's {@code CoordinatorDynamicCatalogManager.loadInitialCatalogs} verifies the
 * invariant in {@code StoredCatalog.loadProperties()}'s returned name == {@code name()}.
 * We enforce it by constructing both from the same CatalogName.
 */
public class BaleiaStoredCatalog
        implements StoredCatalog
{
    private final CatalogName name;
    private final CatalogRow row;
    private final Map<String, String> resolvedProperties;
    private final ConnectorName connectorName;

    public BaleiaStoredCatalog(CatalogRow row, ConnectorName connectorName, Map<String, String> resolvedProperties)
    {
        this.row = row;
        this.name = new CatalogName(row.catalogName());
        this.connectorName = connectorName;
        this.resolvedProperties = ImmutableMap.copyOf(resolvedProperties);
    }

    @Override
    public CatalogName name()
    {
        return name;
    }

    @Override
    public CatalogProperties loadProperties()
    {
        return new CatalogProperties(
                name,
                BaleiaCatalogStore.computeCatalogVersion(name, connectorName, resolvedProperties),
                connectorName,
                resolvedProperties);
    }
}