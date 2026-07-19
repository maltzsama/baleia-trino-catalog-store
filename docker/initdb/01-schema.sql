-- Dev bootstrap for the baleia-trino-catalog-store compose stack.
--
-- This is the minimal schema the Trino plugin needs to boot. It mirrors
-- CR-014 §4.1; the backend Go repository owns the canonical migration —
-- keep them in sync. Production deploys should apply the backend migration,
-- not this file.
--
-- Docker entrypoint runs *.sh then *.sql alphabetically against the
-- POSTGRES_DB (baleia) as the superuser.

-- ════════════════════════════════════════════════════════════════════════
-- Clusters
-- ════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS trino_clusters (
    id          bigserial PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO trino_clusters (name)
VALUES ('default')
ON CONFLICT (name) DO NOTHING;

-- ════════════════════════════════════════════════════════════════════════
-- Catalog registry
-- ════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS trino_catalog_registry (
    cluster_id       bigint       NOT NULL REFERENCES trino_clusters(id),
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

-- Reserved names (mirror of ReservedName in CatalogRow.java). The DB-level
-- CHECK is the first line of defense; the plugin's CatalogRow constructor is
-- the second. If someone bypasses the plugin (manual INSERT), this catches it.
ALTER TABLE trino_catalog_registry
    DROP CONSTRAINT IF EXISTS trino_catalog_registry_name_format;
ALTER TABLE trino_catalog_registry
    ADD CONSTRAINT trino_catalog_registry_name_format
    CHECK (catalog_name ~ '^[a-z][a-z0-9_]{0,62}$'
       AND catalog_name NOT IN ('system', 'jmx', 'tpch', 'tpcds', 'memory')
       AND connector_name ~ '^[a-z][a-z0-9_]{0,62}$');

-- ════════════════════════════════════════════════════════════════════════
-- Seed row: a working tpch catalog so T1/T2 can pass on a clean `up`.
-- Use the built-in tpch connector — no external service required.
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