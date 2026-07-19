package io.baleia.trino.catalogstore;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** A validated row from trino_catalog_registry. */
public record CatalogRow(String catalogName, String connectorName, Map<String, String> properties)
{
    private static final Pattern NAME =
            Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Set<String> RESERVED =
            Set.of("system", "jmx", "tpch", "tpcds", "memory");

    public CatalogRow
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(connectorName, "connectorName is null");
        requireNonNull(properties, "properties is null");

        if (!NAME.matcher(catalogName).matches()) {
            throw new IllegalArgumentException("invalid catalog name: " + catalogName);
        }
        if (RESERVED.contains(catalogName)) {
            throw new IllegalArgumentException("catalog name is reserved by Trino: " + catalogName);
        }
        if (!NAME.matcher(connectorName).matches()) {
            throw new IllegalArgumentException("invalid connector name: " + connectorName);
        }
        if (properties.containsKey("connector.name")) {
            throw new IllegalArgumentException(
                    "connector.name must not be inside properties; use the connector_name column instead");
        }
        properties = Map.copyOf(properties);
    }
}