#!/bin/bash
# Creates/rotates the baleia_trino role and applies the minimum grant set.
# Runs as the postgres superuser (the initdb runs with POSTGRES_USER=baleia).
# The password is read from $BALEIA_TRINO_DB_PASSWORD (set in docker-compose.yml).
#
# Naming: the role and the env var it is created from must agree between
# Postgres (01-schema.sql creates the role's tables; this script creates
# the role itself) and the Trino container (which reads the same env var
# to substitute into catalog-store.properties). Both feed from
# BALEIA_TRINO_DB_PASSWORD so they can never diverge.
#
# Recovery note: the postgres image runs this whole initdb
# directory exactly once — on the first initdb of PGDATA. If this script
# ever aborts halfway (set -euo pipefail + ON_ERROR_STOP=1 will halt on
# any SQL error), the volume ends up partially initialized and the next
# `docker compose up` skips the entire initdb pass because PGDATA already
# exists. Fix any script error, then:
#   docker compose down -v
#   docker compose up -d
# (the `-v` removes the named volume so initdb runs cleanly again).

set -euo pipefail

: "${BALEIA_TRINO_DB_PASSWORD:?BALEIA_TRINO_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'baleia_trino') THEN
        CREATE ROLE baleia_trino LOGIN PASSWORD '${BALEIA_TRINO_DB_PASSWORD}';
    ELSE
        ALTER ROLE baleia_trino LOGIN PASSWORD '${BALEIA_TRINO_DB_PASSWORD}';
    END IF;
END \$\$;

-- Lock the role down to ONLY the catalog tables it needs.
-- Standalone REVOKE requires `ALL TABLES IN SCHEMA` (the bare `TABLES`
-- form exists only inside ALTER DEFAULT PRIVILEGES).
REVOKE ALL ON SCHEMA public                FROM PUBLIC;
REVOKE ALL ON SCHEMA public                FROM baleia_trino;
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM baleia_trino;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM baleia_trino;

GRANT USAGE ON SCHEMA public                                TO baleia_trino;
GRANT SELECT           ON trino_clusters                    TO baleia_trino;
GRANT SELECT, INSERT, UPDATE, DELETE ON trino_catalog_registry TO baleia_trino;
SQL