#!/bin/bash
# !!  DEV-ONLY. This script bootstraps the dev compose stack.
#     The REVOKE ALL ON SCHEMA public FROM PUBLIC below is safe here (the
#     `baleia` user is the DB superuser), but in production it may remove
#     USAGE from legitimate roles. In production, apply the canonical
#     backend Go migration and scope REVOKE to baleia_trino.
#
# Creates/rotates the baleia_trino role and applies the minimum grant set.
# Password comes from $BALEIA_TRINO_DB_PASSWORD — the SAME env var the Trino
# container reads for catalog-store.properties substitution, so they cannot
# diverge.
#
# Recovery: the postgres image runs initdb/ once, on first PGDATA population.
# If this script aborts, the volume is half-initialized and the next `up`
# skips initdb because PGDATA exists. Fix the error then:
#   docker compose down -v
#   docker compose up -d

set -euo pipefail

: "${BALEIA_TRINO_DB_PASSWORD:?BALEIA_TRINO_DB_PASSWORD must be set}"

# Password enters via -v (psql level), NEVER interpolated into SQL text by bash.
# The heredoc is quoted (<<'SQL') so bash leaves everything alone.
# :'pwd'  -> psql produces a correctly-escaped SQL literal
# %L      -> format() quotes the value for generated DDL
# No double-escaping: each layer quotes once.
psql -v ON_ERROR_STOP=1 \
     -v pwd="$BALEIA_TRINO_DB_PASSWORD" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE baleia_trino LOGIN PASSWORD %L', :'pwd')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'baleia_trino')
\gexec

SELECT format('ALTER ROLE baleia_trino LOGIN PASSWORD %L', :'pwd')
 WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'baleia_trino')
\gexec

-- Lock the role down to ONLY the catalog tables it needs.
-- Standalone REVOKE requires `ALL TABLES IN SCHEMA` (the bare `TABLES`
-- form exists only inside ALTER DEFAULT PRIVILEGES).
REVOKE ALL ON SCHEMA public                  FROM PUBLIC;
REVOKE ALL ON SCHEMA public                  FROM baleia_trino;
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM baleia_trino;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM baleia_trino;

GRANT USAGE ON SCHEMA public                                   TO baleia_trino;
GRANT SELECT ON trino_clusters                                 TO baleia_trino;
GRANT SELECT, INSERT, UPDATE, DELETE ON trino_catalog_registry TO baleia_trino;
SQL
