package io.baleia.trino.catalogstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.spi.TrinoException;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.StandardErrorCode.CATALOG_STORE_ERROR;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Database
{
    private static final Logger log = Logger.get(Database.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** D3: total attempts on a full connection failure, with exponential backoff. */
    private static final int MAX_CONNECT_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** M4: cap a `sync_error` write so a stack trace or large blob can't blow up the column. */
    private static final int SYNC_ERROR_MAX = 1000;

    private static final String SELECT_ALL = """
            SELECT r.catalog_name, r.connector_name, r.properties::text
              FROM trino_catalog_registry r
              JOIN trino_clusters c ON c.id = r.cluster_id
             WHERE c.name = ? AND r.enabled
             ORDER BY r.catalog_name
            """;

    private static final String SELECT_ONE = """
            SELECT r.properties::text
              FROM trino_catalog_registry r
              JOIN trino_clusters c ON c.id = r.cluster_id
             WHERE c.name = ? AND r.catalog_name = ? AND r.enabled
            """;

    private static final String SELECT_CLUSTER = """
            SELECT 1 FROM trino_clusters WHERE name = ?
            """;

    private static final String UPSERT = """
            INSERT INTO trino_catalog_registry
                   (cluster_id, catalog_name, connector_name, properties,
                    catalog_version, enabled, sync_status, sync_error, updated_by, updated_at)
            VALUES ((SELECT id FROM trino_clusters WHERE name = ?),
                    ?, ?, ?::jsonb, ?, true, 'synced', NULL, 'trino', now())
            ON CONFLICT (cluster_id, catalog_name) DO UPDATE SET
                   connector_name  = EXCLUDED.connector_name,
                   properties      = EXCLUDED.properties,
                   catalog_version = EXCLUDED.catalog_version,
                   enabled         = true,
                   sync_status     = 'synced',
                   sync_error      = NULL,
                   updated_by      = 'trino',
                   updated_at      = now()
            """;

    private static final String SOFT_DELETE = """
            UPDATE trino_catalog_registry r
               SET enabled = false, updated_by = 'trino', updated_at = now()
              FROM trino_clusters c
             WHERE c.id = r.cluster_id AND c.name = ? AND r.catalog_name = ?
            """;

    private static final String MARK_ERROR = """
            UPDATE trino_catalog_registry r
               SET sync_status = 'error', sync_error = ?, updated_by = 'trino', updated_at = now()
              FROM trino_clusters c
             WHERE c.id = r.cluster_id AND c.name = ? AND r.catalog_name = ?
            """;

    private final PGSimpleDataSource dataSource;
    private final String clusterName;
    private final Duration connectTimeout;

    // M2: validate the cluster row exists once per Database instance; flip on first hit.
    private volatile boolean clusterValidated;

    @Inject
    public Database(BaleiaCatalogStoreConfig config)
    {
        this.clusterName = config.getClusterName();
        this.connectTimeout = config.getConnectTimeout();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(config.getJdbcUrl());
        ds.setUser(config.getUsername());
        ds.setPassword(config.getPassword());
        // PGSimpleDataSource takes int seconds. Airlift reflects "10s"/"1m" for us.
        ds.setConnectTimeout((int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) connectTimeout.getValue(SECONDS))));
        this.dataSource = ds;
    }

    @FunctionalInterface
    private interface SqlAction<T>
    {
        T apply(Connection c) throws Exception;
    }

    /**
     * D3: connection acquisitions go through retry-with-backoff, then fail-fast on exhaustion.
     * The action runs on the established connection; retry happens only on SQLException
     * (transient DB unavailability). Non-SQL exceptions from the action propagate immediately
     * so a permanent error (constraint violation, malformed JSON, missing cluster) does not
     * waste five rounds of retry.
     */
    private <T> T retrying(SqlAction<T> action)
    {
        long backoffMs = INITIAL_BACKOFF_MS;
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                validateClusterOnce(c);
                return action.apply(c);
            }
            catch (SQLException e) {
                last = e;
                if (attempt == MAX_CONNECT_ATTEMPTS) {
                    break;
                }
                log.warn("Baleia DB connect attempt %d/%d failed: %s. Retrying in %dms",
                        attempt, MAX_CONNECT_ATTEMPTS, e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TrinoException(CATALOG_STORE_ERROR,
                            "Interrupted during Baleia DB retry", ie);
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
            catch (Exception e) {
                // Non-SQL exception from validate or action: do not retry, propagate.
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new TrinoException(CATALOG_STORE_ERROR, "Baleia DB action failed", e);
            }
        }
        throw new TrinoException(CATALOG_STORE_ERROR,
                format("Failed to connect to Baleia DB after %d attempts (cluster=%s): %s",
                        MAX_CONNECT_ATTEMPTS, clusterName, last == null ? "" : last.getMessage()),
                last);
    }

    /**
     * M2: confirm the cluster row exists. Called once per Database lifetime. On a missing row
     * throws a non-retry RuntimeException so retrying() surfaces it immediately as a fail-fast.
     */
    private void validateClusterOnce(Connection c)
            throws SQLException
    {
        if (clusterValidated) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(SELECT_CLUSTER)) {
            ps.setString(1, clusterName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new TrinoException(CATALOG_STORE_ERROR,
                            format("Cluster '%s' not found in trino_clusters. Check baleia.cluster-name.", clusterName));
                }
            }
        }
        clusterValidated = true;
    }

    /**
     * Load all enabled rows. Per-row parse/resolve failures log + markError and skip the row
     * (a single bad row must not prevent Trino from starting). Connection-level failure
     * bubbles up as TrinoException and fails the boot — see D3.
     */
    public List<CatalogRow> loadAll()
    {
        List<CatalogRow> rows = retrying(c -> {
            List<CatalogRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT_ALL)) {
                ps.setString(1, clusterName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        try {
                            out.add(new CatalogRow(name, rs.getString(2), parseFlatJson(rs.getString(3))));
                        }
                        catch (RuntimeException e) {
                            if (e instanceof TrinoException) {
                                throw e;
                            }
                            log.warn(e, "Catalog '%s' skipped: %s", name, e.getMessage());
                            markError(name, e.getMessage());
                        }
                    }
                }
            }
            return out;
        });
        log.info("Loaded %d catalogs from Baleia (cluster=%s)", rows.size(), clusterName);
        return rows;
    }

    public Optional<Map<String, String>> loadProperties(String catalogName)
    {
        return retrying(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT_ONE)) {
                ps.setString(1, clusterName);
                ps.setString(2, catalogName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(parseFlatJson(rs.getString(1)));
                    }
                    return Optional.<Map<String, String>>empty();
                }
            }
        });
    }

    public void upsert(CatalogRow row, String version)
    {
        try {
            retrying(c -> {
                try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                    ps.setString(1, clusterName);
                    ps.setString(2, row.catalogName());
                    ps.setString(3, row.connectorName());
                    ps.setString(4, MAPPER.writeValueAsString(row.properties()));
                    ps.setString(5, version);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        catch (TrinoException e) {
            throw e;
        }
        catch (RuntimeException e) {
            // N4: surface as CATALOG_STORE_ERROR so the client / Lens can categorize.
            // Crucially do NOT swallow — see Trino bug #23557: in-memory state must
            // not survive if the row was not persisted.
            throw new TrinoException(CATALOG_STORE_ERROR,
                    format("Failed to persist catalog '%s': %s", row.catalogName(), e.getMessage()), e);
        }
    }

    public void softDelete(String catalogName)
    {
        try {
            retrying(c -> {
                try (PreparedStatement ps = c.prepareStatement(SOFT_DELETE)) {
                    ps.setString(1, clusterName);
                    ps.setString(2, catalogName);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        catch (TrinoException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new TrinoException(CATALOG_STORE_ERROR,
                    format("Failed to remove catalog '%s': %s", catalogName, e.getMessage()), e);
        }
    }

    /**
     * Best-effort write of a failure marker for a single bad catalog. Visible to
     * {@link BaleiaCatalogStore} so eager resolution failures in {@code getCatalogs()}
     * can be marked the same way parse failures in {@code loadAll()} already are.
     */
    public void markError(String catalogName, String message)
    {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(MARK_ERROR)) {
            ps.setString(1, truncate(message, SYNC_ERROR_MAX));
            ps.setString(2, clusterName);
            ps.setString(3, catalogName);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            // Best-effort: if we can't even write the error, log and move on.
            log.warn(e, "Failed to mark error on '%s'", catalogName);
        }
    }

    private static String truncate(String s, int max)
    {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Accepts only flat string -> string JSON objects. */
    static Map<String, String> parseFlatJson(String json)
    {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("properties is not a JSON object");
            }
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual()) {
                    throw new IllegalArgumentException(
                            "property '" + e.getKey() + "' is not a string; Trino requires varchar for all catalog properties");
                }
                out.put(e.getKey(), e.getValue().asText());
            });
            return out;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("properties is not valid JSON", e);
        }
    }
}