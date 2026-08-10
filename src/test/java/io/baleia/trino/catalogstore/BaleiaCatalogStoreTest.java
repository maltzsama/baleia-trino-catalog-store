package io.baleia.trino.catalogstore;

import io.trino.spi.TrinoException;
import io.trino.spi.catalog.CatalogName;
import io.trino.spi.catalog.CatalogProperties;
import io.trino.spi.catalog.CatalogStore;
import io.trino.spi.connector.CatalogVersion;
import io.trino.spi.connector.ConnectorName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaleiaCatalogStoreTest
{
    @Test
    void getCatalogsReturnsResolvedStoredCatalogs()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.rows.add(new CatalogRow("vendas", "iceberg", Map.of(
                "iceberg.auth.token", "@baleia-secret[vault:iceberg.auth.token]")));
        db.secretCatalog = Map.of("iceberg.auth.token", "secret-value");
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        List<CatalogStore.StoredCatalog> catalogs = new ArrayList<>(store.getCatalogs());

        assertEquals(1, catalogs.size());
        CatalogStore.StoredCatalog catalog = catalogs.get(0);
        assertEquals(new CatalogName("vendas"), catalog.name());
        CatalogProperties props = catalog.loadProperties();
        assertEquals("secret-value", props.properties().get("iceberg.auth.token"));
        assertEquals(new ConnectorName("iceberg"), props.connectorName());
    }

    @Test
    void getCatalogsSkipsBadRowsAndMarksError()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.rows.add(new CatalogRow("boa", "tpch", Map.of()));
        // Valid row that fails eager secret resolution (dangling reference) -> skipped + marked.
        db.rows.add(new CatalogRow("quebrada", "tpch", Map.of(
                "k", "@baleia-secret[missing:segredo]")));
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        List<CatalogStore.StoredCatalog> catalogs = new ArrayList<>(store.getCatalogs());

        assertEquals(1, catalogs.size());
        assertEquals(new CatalogName("boa"), catalogs.get(0).name());
        assertEquals(List.of("quebrada"), db.markedErrors.stream().map(Mark::catalogName).toList());
    }

    @Test
    void getCatalogsReachesDatabaseViaLoadAll()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.rows.add(new CatalogRow("boa", "tpch", Map.of()));
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        store.getCatalogs();

        assertEquals(1, db.loadAllCalls);
    }

    @Test
    void createCatalogPropertiesResolvesSecretsAndComputesVersion()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.secretCatalog = Map.of("iceberg.auth.token", "resolvido");
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        CatalogProperties props = store.createCatalogProperties(
                new CatalogName("novo"),
                new ConnectorName("iceberg"),
                Map.of("iceberg.auth.token", "@baleia-secret[vault:iceberg.auth.token]"));

        assertEquals("resolvido", props.properties().get("iceberg.auth.token"));
        // Version is deterministic and non-empty; full algorithm covered by ComputeCatalogVersionTest.
        assertTrue(props.version().toString().length() == 64, "expected SHA-256 hex");
    }

    @Test
    void addOrReplaceCatalogPersistsRow()
    {
        RecordingDatabase db = new RecordingDatabase();
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        CatalogName name = new CatalogName("novo");
        ConnectorName connector = new ConnectorName("tpch");
        CatalogProperties props = store.createCatalogProperties(name, connector, Map.of("a", "b"));
        store.addOrReplaceCatalog(props);

        assertEquals(1, db.upserts.size());
        Upsert u = db.upserts.get(0);
        assertEquals("novo", u.row.catalogName());
        assertEquals("tpch", u.row.connectorName());
        assertEquals(Map.of("a", "b"), u.row.properties());
        assertEquals(props.version().toString(), u.version);
    }

    @Test
    void removeCatalogSoftDeletes()
    {
        RecordingDatabase db = new RecordingDatabase();
        SecretResolver resolver = new SecretResolver(db);
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        store.removeCatalog(new CatalogName("velho"));

        assertEquals(List.of("velho"), db.softDeletes);
    }

    // ── Recording Database stub ──────────────────────────────────────────────

    private static final class RecordingDatabase extends Database
    {
        final List<CatalogRow> rows = new ArrayList<>();
        final List<Upsert> upserts = new ArrayList<>();
        final List<String> softDeletes = new ArrayList<>();
        final List<Mark> markedErrors = new ArrayList<>();
        Map<String, String> secretCatalog;
        int loadAllCalls;

        RecordingDatabase()
        {
            super(new BaleiaCatalogStoreConfig()
                    .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                    .setUsername("u")
                    .setPassword("p"));
        }

        @Override
        public List<CatalogRow> loadAll()
        {
            loadAllCalls++;
            return rows;
        }

        @Override
        public Optional<Map<String, String>> loadProperties(String catalogName)
        {
            if (secretCatalog == null) {
                return Optional.empty();
            }
            return Optional.of(secretCatalog);
        }

        @Override
        public void markError(String catalogName, String message)
        {
            markedErrors.add(new Mark(catalogName, message));
        }

        @Override
        public void upsert(CatalogRow row, String version)
        {
            upserts.add(new Upsert(row, version));
        }

        @Override
        public void softDelete(String catalogName)
        {
            softDeletes.add(catalogName);
        }
    }

    private record Upsert(CatalogRow row, String version) {}

    private record Mark(String catalogName, String message) {}
}
