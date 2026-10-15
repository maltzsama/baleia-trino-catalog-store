package io.baleia.trino.catalogstore;

import io.trino.spi.TrinoException;
import io.trino.spi.catalog.CatalogName;
import io.trino.spi.catalog.CatalogProperties;
import io.trino.spi.catalog.CatalogStore;
import io.trino.spi.connector.CatalogVersion;
import io.trino.spi.connector.ConnectorName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    @TempDir
    Path tempDir;

    @Test
    void getCatalogsReturnsResolvedStoredCatalogs()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.rows.add(new CatalogRow("vendas", "iceberg", Map.of(
                "iceberg.auth.token", "@baleia-secret[vault:iceberg.auth.token]")));
        db.secretCatalog = Map.of("iceberg.auth.token", "secret-value");
        SecretResolver resolver = new SecretResolver(db, baseConfig());
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
        SecretResolver resolver = new SecretResolver(db, baseConfig());
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        List<CatalogStore.StoredCatalog> catalogs = new ArrayList<>(store.getCatalogs());

        assertEquals(1, catalogs.size());
        assertEquals(new CatalogName("boa"), catalogs.get(0).name());
        assertEquals(List.of("quebrada"), db.markedErrors.stream().map(Mark::catalogName).toList());
        // Only the row that loaded is cleared; the broken one keeps its error marker.
        assertEquals(List.of("boa"), db.syncedCatalogs);
    }

    @Test
    void getCatalogsReachesDatabaseViaLoadAll()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.rows.add(new CatalogRow("boa", "tpch", Map.of()));
        SecretResolver resolver = new SecretResolver(db, baseConfig());
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);

        store.getCatalogs();

        assertEquals(1, db.loadAllCalls);
    }

    @Test
    void createCatalogPropertiesResolvesSecretsAndComputesVersion()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.secretCatalog = Map.of("iceberg.auth.token", "resolvido");
        SecretResolver resolver = new SecretResolver(db, baseConfig());
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
        SecretResolver resolver = new SecretResolver(db, baseConfig());
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
    void addOrReplaceCatalogPersistsPlaceholdersNotResolvedSecrets() throws IOException
    {
        Files.writeString(tempDir.resolve("db-password.txt"), "from-file\n");
        RecordingDatabase db = new RecordingDatabase();
        db.secretCatalog = Map.of("token", "from-catalog");
        SecretResolver resolver = new SecretResolver(db, configWithBaseDir(tempDir));
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);
        Map<String, String> ddl = Map.of(
                "plain", "value",
                "password", "@baleia-secret[file:db-password.txt]",
                "user", "@baleia-secret[env:PATH]",
                "token", "@baleia-secret[vault:token]");

        CatalogProperties props = store.createCatalogProperties(new CatalogName("novo"), new ConnectorName("tpch"), ddl);
        store.addOrReplaceCatalog(props);

        // The connector gets the resolved values...
        assertEquals("from-file", props.properties().get("password"));
        assertEquals(System.getenv("PATH"), props.properties().get("user"));
        assertEquals("from-catalog", props.properties().get("token"));
        // ...but the database row keeps the original text.
        assertEquals(ddl, db.upserts.get(0).row.properties());
        // The version is still computed over the resolved values.
        assertEquals(props.version().toString(), db.upserts.get(0).version);
    }

    @Test
    void addOrReplaceCatalogRefusesCatalogNotCreatedByThisStore()
    {
        RecordingDatabase db = new RecordingDatabase();
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, new SecretResolver(db, baseConfig()));
        CatalogName name = new CatalogName("novo");
        ConnectorName connector = new ConnectorName("tpch");
        CatalogProperties foreign = new CatalogProperties(
                name, new CatalogVersion("0".repeat(64)), connector, Map.of("password", "resolved-secret"));

        assertThrows(TrinoException.class, () -> store.addOrReplaceCatalog(foreign));
        assertTrue(db.upserts.isEmpty());
    }

    @Test
    void addOrReplaceCatalogRefusesStaleVersion()
    {
        RecordingDatabase db = new RecordingDatabase();
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, new SecretResolver(db, baseConfig()));
        CatalogName name = new CatalogName("novo");
        ConnectorName connector = new ConnectorName("tpch");
        CatalogProperties first = store.createCatalogProperties(name, connector, Map.of("a", "1"));
        store.createCatalogProperties(name, connector, Map.of("a", "2"));

        assertThrows(TrinoException.class, () -> store.addOrReplaceCatalog(first));
        assertTrue(db.upserts.isEmpty());
    }

    @Test
    void rejectingStaleVersionKeepsTheNewerPendingEntry()
    {
        RecordingDatabase db = new RecordingDatabase();
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, new SecretResolver(db, baseConfig()));
        CatalogName name = new CatalogName("novo");
        ConnectorName connector = new ConnectorName("tpch");
        CatalogProperties stale = store.createCatalogProperties(name, connector, Map.of("a", "1"));
        CatalogProperties current = store.createCatalogProperties(name, connector, Map.of("a", "2"));

        assertThrows(TrinoException.class, () -> store.addOrReplaceCatalog(stale));
        store.addOrReplaceCatalog(current);

        assertEquals(Map.of("a", "2"), db.upserts.get(0).row.properties());
    }

    @Test
    void rotatedFileSecretTakesEffectOnNextBootWithoutRecreatingCatalog() throws IOException
    {
        Files.writeString(tempDir.resolve("db-password.txt"), "old");
        RecordingDatabase db = new RecordingDatabase();
        SecretResolver resolver = new SecretResolver(db, configWithBaseDir(tempDir));
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, resolver);
        Map<String, String> ddl = Map.of("password", "@baleia-secret[file:db-password.txt]");

        store.addOrReplaceCatalog(store.createCatalogProperties(new CatalogName("novo"), new ConnectorName("tpch"), ddl));
        // What the database now holds is what getCatalogs() reads at the next boot.
        db.rows.add(db.upserts.get(0).row);
        Files.writeString(tempDir.resolve("db-password.txt"), "new");

        CatalogProperties booted = new ArrayList<>(store.getCatalogs()).get(0).loadProperties();
        assertEquals("new", booted.properties().get("password"));
    }

    @Test
    void selfReferencedSecretSurvivesTheNextBoot()
    {
        // Baleia writes the real value into the row, then sends CREATE CATALOG with a
        // placeholder pointing at that row. The row the plugin persists must still hold
        // the value, or the next boot skips the catalog as a circular reference.
        RecordingDatabase db = new RecordingDatabase();
        db.secretCatalog = Map.of("iceberg.rest-catalog.oauth2.credential", "admin:s3cret");
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, new SecretResolver(db, baseConfig()));
        Map<String, String> ddl = Map.of(
                "iceberg.rest-catalog.oauth2.credential", "@baleia-secret[lake:iceberg.rest-catalog.oauth2.credential]",
                "iceberg.rest-catalog.uri", "http://polaris:8181/api/catalog");

        store.addOrReplaceCatalog(store.createCatalogProperties(new CatalogName("lake"), new ConnectorName("iceberg"), ddl));
        CatalogRow persisted = db.upserts.get(0).row;
        assertEquals("admin:s3cret", persisted.properties().get("iceberg.rest-catalog.oauth2.credential"));

        // Next boot: the row is now what loadProperties() and loadAll() return.
        db.rows.add(persisted);
        db.secretCatalog = persisted.properties();
        List<CatalogStore.StoredCatalog> booted = new ArrayList<>(store.getCatalogs());
        assertEquals(1, booted.size());
        assertTrue(db.markedErrors.isEmpty(), "no catalog may be skipped: " + db.markedErrors);
        assertEquals("admin:s3cret", booted.get(0).loadProperties().properties().get("iceberg.rest-catalog.oauth2.credential"));
    }

    @Test
    void otherCatalogsReferencesStayReferences()
    {
        RecordingDatabase db = new RecordingDatabase();
        db.secretCatalog = Map.of("password", "vault-value");
        BaleiaCatalogStore store = new BaleiaCatalogStore(db, new SecretResolver(db, baseConfig()));

        store.addOrReplaceCatalog(store.createCatalogProperties(new CatalogName("vendas"), new ConnectorName("tpch"),
                Map.of("password", "@baleia-secret[vault:password]")));

        assertEquals("@baleia-secret[vault:password]", db.upserts.get(0).row.properties().get("password"));
    }

    @Test
    void removeCatalogSoftDeletes()
    {
        RecordingDatabase db = new RecordingDatabase();
        SecretResolver resolver = new SecretResolver(db, baseConfig());
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
        final List<String> syncedCatalogs = new ArrayList<>();
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
        public void markSynced(java.util.Collection<String> catalogNames)
        {
            syncedCatalogs.addAll(catalogNames);
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

    private static BaleiaCatalogStoreConfig baseConfig()
    {
        return new BaleiaCatalogStoreConfig()
                .setJdbcUrl("jdbc:postgresql://localhost:5432/_unused")
                .setUsername("u")
                .setPassword("p");
    }

    private BaleiaCatalogStoreConfig configWithBaseDir(Path dir)
    {
        return baseConfig().setSecretFileBaseDir(dir.toString());
    }
}
