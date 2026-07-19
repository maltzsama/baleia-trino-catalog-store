#!/bin/bash
# Creates/rotates the baleia_trino role and applies the minimum grant set.
# Runs as the postgres superuser (initdb runs with POSTGRES_USER=baleia).
# The password is read from $BALEIA_TRINO_PASSWORD (set in compose env).

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

-- Lock the role down to the catalog tables only.
REVOKE ALL ON SCHEMA public              FROM PUBLIC;
REVOKE ALL ON SCHEMA public              FROM baleia_trino;
REVOKE ALL ON TABLES IN SCHEMA public    FROM baleia_trino;

GRANT USAGE ON SCHEMA public                                TO baleia_trino;
GRANT SELECT           ON trino_clusters                    TO baleia_trino;
GRANT SELECT, INSERT, UPDATE, DELETE ON trino_catalog_registry TO baleia_trino;
SQL