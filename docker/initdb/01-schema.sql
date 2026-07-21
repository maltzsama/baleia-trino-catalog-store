-- Dev bootstrap for the baleia-trino-catalog-store compose stack.
--
-- Schema with the columns the plugin needs to boot:
--   * trino_clusters.id  UUID (matches the production migration schema)
--   * trino_catalog_registry + CHECK on name format, reserved names,
--     sync_status, updated_by
-- The backend Go repository owns the canonical migration; this file only
-- reproduces the columns the plugin touches at boot/DDl, with the same
-- CHECKs so manual inserts surface the same errors here as in production.
--
-- Correction about the entrypoint: docker-library/postgres
-- processes /docker-entrypoint-initdb.d in a single alphabetical pass —
-- NOT "*.sh then *.sql". The role bootstrap in 02-role.sh runs AFTER this
-- file only because "02-" sorts after "01-". Don't rename files trusting
-- the "*.sh first" claim later found in some blog posts.
-- Both .sh AND .sql in the same directory are run once, in sort order, on
-- the first initdb of the data dir.

-- ════════════════════════════════════════════════════════════════════════
-- Clusters
-- ════════════════════════════════════════════════════════════════════════
-- UUID for trino_clusters.id. The plugin joins by
-- trino_clusters.name, so id type is opaque to it; we keep the same type
-- as the canonical migration to avoid a surprise during integration tests.
-- gen_random_uuid() is core since PostgreSQL 13; pgcrypto is not needed.
CREATE TABLE IF NOT EXISTS trino_clusters (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text         NOT NULL UNIQUE,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

INSERT INTO trino_clusters (name)
VALUES ('default')
ON CONFLICT (name) DO NOTHING;

-- ════════════════════════════════════════════════════════════════════════
-- Catalog registry
-- ════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS trino_catalog_registry (
    cluster_id       uuid         NOT NULL REFERENCES trino_clusters(id),
    catalog_name     text         NOT NULL,
    connector_name   text         NOT NULL,
    properties       jsonb        NOT NULL,
    catalog_version  text,
    enabled          boolean      NOT NULL DEFAULT true,
    sync_status      text         NOT NULL DEFAULT 'pending',
    sync_error       text,
    updated_by       text         NOT NULL DEFAULT 'baleia',
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (cluster_id, catalog_name)
);

CREATE INDEX IF NOT EXISTS trino_catalog_registry_cluster_enabled_idx
    ON trino_catalog_registry (cluster_id) WHERE enabled;

-- Reserved names + format mirror CatalogRow.java. The DB-level CHECK is
-- the first line of defense; the plugin's CatalogRow constructor is the
-- second. If someone bypasses the plugin (manual INSERT), this catches it.
ALTER TABLE trino_catalog_registry
    DROP CONSTRAINT IF EXISTS trino_catalog_registry_name_format;
ALTER TABLE trino_catalog_registry
    ADD CONSTRAINT trino_catalog_registry_name_format
    CHECK (catalog_name ~ '^[a-z][a-z0-9_]{0,62}$'
       AND catalog_name NOT IN ('system', 'jmx', 'tpch', 'tpcds', 'memory')
       AND connector_name ~ '^[a-z][a-z0-9_]{0,62}$');

ALTER TABLE trino_catalog_registry
    DROP CONSTRAINT IF EXISTS trino_catalog_registry_sync_status_format;
ALTER TABLE trino_catalog_registry
    ADD CONSTRAINT trino_catalog_registry_sync_status_format
    CHECK (sync_status IN ('pending', 'synced', 'error'));

ALTER TABLE trino_catalog_registry
    DROP CONSTRAINT IF EXISTS trino_catalog_registry_updated_by_format;
ALTER TABLE trino_catalog_registry
    ADD CONSTRAINT trino_catalog_registry_updated_by_format
    CHECK (updated_by IN ('baleia', 'trino'));

-- ════════════════════════════════════════════════════════════════════════
-- Seed row: a working tpch catalog so T1/T2 can pass on a clean `up`.
-- Uses the built-in tpch connector — no external service required.
-- ════════════════════════════════════════════════════════════════════════
INSERT INTO trino_catalog_registry
    (cluster_id, catalog_name, connector_name, properties,
     enabled, sync_status, updated_by)
VALUES (
    (SELECT id FROM trino_clusters WHERE name = 'default'),
    'tpch_teste', 'tpch',
    '{"tpch.splits-per-node": "4"}'::jsonb,
    true, 'pending', 'baleia')
ON CONFLICT (cluster_id, catalog_name) DO NOTHING;