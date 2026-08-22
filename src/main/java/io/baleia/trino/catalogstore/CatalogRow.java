package io.baleia.trino.catalogstore;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * An immutable, validated row from {@code trino_catalog_registry}.
 *
 * <p>The compact constructor enforces invariants that mirror the SQL
 * {@code CHECK} constraints in {@code 01-schema.sql}:
 * <ul>
 *   <li>{@code catalogName} and {@code connectorName} must match
 *       {@code ^[a-z][a-z0-9_]{0,62}$}.</li>
 *   <li>{@code catalogName} must not be a reserved name
 *       ({@code system}, {@code jmx}, {@code tpch}, {@code tpcds},
 *       {@code memory}, {@code env}, {@code file}).</li>
 *   <li>{@code properties} must not contain {@code connector.name} — the
 *       connector is stored in a separate column.</li>
 * </ul>
 *
 * <p>Properties are defensively copied via {@link Map#copyOf} to prevent
 * external mutation.
 *
 * @param catalogName   the catalog name in Trino
 * @param connectorName the connector type (e.g. "iceberg", "tpch")
 * @param properties    flat string-to-string catalog properties
 */
public record CatalogRow(String catalogName, String connectorName, Map<String, String> properties)
{
    private static final Pattern NAME =
            Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Set<String> RESERVED =
            Set.of("system", "jmx", "tpch", "tpcds", "memory", "env", "file");

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