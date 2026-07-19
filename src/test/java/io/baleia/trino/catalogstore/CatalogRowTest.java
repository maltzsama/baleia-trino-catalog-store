package io.baleia.trino.catalogstore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogRowTest
{
    @Test
    void acceptsValidRow()
    {
        CatalogRow row = new CatalogRow("vendas", "iceberg", Map.of("iceberg.catalog.type", "rest"));
        assertEquals("vendas", row.catalogName());
        assertEquals("iceberg", row.connectorName());
        assertEquals("rest", row.properties().get("iceberg.catalog.type"));
    }

    @Test
    void rejectsNullFields()
    {
        assertThrows(NullPointerException.class,
                () -> new CatalogRow(null, "tpch", Map.of()));
        assertThrows(NullPointerException.class,
                () -> new CatalogRow("x", null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> new CatalogRow("x", "tpch", null));
    }

    @Test
    void rejectsInvalidCatalogName()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("Vendas", "tpch", Map.of()));
        assertTrue(e.getMessage().contains("invalid catalog name"));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("1vflush", "tpch", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("has-dash", "tpch", Map.of()));
    }

    @Test
    void rejectsReservedName()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("system", "tpch", Map.of()));
        assertTrue(e.getMessage().contains("reserved"));
    }

    @Test
    void rejectsInvalidConnectorName()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("vendas", "iceberg-rest", Map.of()));
        assertTrue(e.getMessage().contains("invalid connector name"));
    }

    @Test
    void rejectsConnectorNameInsideProperties()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CatalogRow("vendas", "iceberg",
                        new java.util.HashMap<>(Map.of("connector.name", "iceberg"))));
        assertTrue(e.getMessage().contains("connector.name"));
    }

    @Test
    void propsAreImmutable()
    {
        CatalogRow row = new CatalogRow("vendas", "iceberg", new java.util.HashMap<>(Map.of("a", "b")));
        assertThrows(UnsupportedOperationException.class,
                () -> row.properties().put("c", "d"));
    }
}