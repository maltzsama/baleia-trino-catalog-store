#!/usr/bin/env bash
# Runs T1-T9 against a running `docker compose up -d` stack.
# Each step prints PASS/FAIL and the table at the end summarizes the result.
#
# Usage:
#   cd docker && ./acceptance.sh
#
# All `trino` CLI invocations use --output-format TSV (the default
# of `--execute` is CSV, and quoting/parsing broke equal-string checks and
# leaked the secret query under T8). All psql invocations go through
# `docker compose exec -T postgres psql ...` so this script never requires
# a local psql client (E6).

set -u

# PSQL: -c makes psql treat the next argument as a SQL command instead of
# trying to connect to a database named "SELECT ...".
PSQL=(docker compose exec -T postgres psql -U baleia -d baleia -t -A -v ON_ERROR_STOP=1 -c)
# --output-format TSV (default is CSV, which wraps values in
# double quotes and breaks the equality/grep checks; TSV is unquoted).
# --execute takes the next argument as a flag value (= not needed).
TRINO=(docker compose exec -T trino trino --output-format TSV --execute)

declare -a RESULTS=()
pass() { RESULTS+=("PASS  $1"); }
fail() { RESULTS+=("FAIL  $1 -> $2"); }

# B9a: Pre-check that the stack is up.
if ! docker compose ps --services --filter status=running 2>/dev/null | grep -qx postgres; then
    echo "ERRO: the stack is not running. Run 'docker compose up -d' in this directory first." >&2
    exit 1
fi

# B9b: Cleanup orphan catalogs on interrupt / exit.
cleanup() {
    "${TRINO[@]}" "DROP CATALOG IF EXISTS tpch_dois" >/dev/null 2>&1 || true
    "${TRINO[@]}" "DROP CATALOG IF EXISTS seg"       >/dev/null 2>&1 || true
    "${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false
                   WHERE catalog_name IN ('tpch_dois', 'seg', 'quebrado');" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Wait for trino CLI to respond; used after `docker compose restart trino`.
wait_for_trino() {
    for _ in {1..60}; do
        if "${TRINO[@]}" "SELECT 1" 2>/dev/null | grep -qE '^[[:space:]]*1[[:space:]]*$'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Reset the seed row to its known state so re-runs are reproducible.
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = true, sync_status = 'pending', sync_error = NULL WHERE catalog_name = 'tpch_teste';" >/dev/null 2>&1
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name IN ('tpch_dois', 'seg');" >/dev/null 2>&1

# ── T1 — read on boot ─────────────────────────────────────────────────────
if "${TRINO[@]}" "SHOW CATALOGS" 2>/dev/null | grep -qE '^[[:space:]]*tpch_teste[[:space:]]*$'; then
    pass "T1 read on boot"
else
    fail "T1 read on boot" "tpch_teste not in SHOW CATALOGS"
fi

# ── T2 — catalog actually works ─────────────────────────────────────────────
count=$("${TRINO[@]}" "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]')
if [[ "$count" == "25" ]]; then
    pass "T2 catalog is queryable"
else
    fail "T2 catalog is queryable" "expected 25, got '$count'"
fi

# ── T3 — persists across restart ──────────────────────────────────────────────
docker compose restart trino >/dev/null 2>&1
if ! wait_for_trino; then
    fail "T3 read persists across restart" "Trino did not respond within 60s after restart"
else
    count=$("${TRINO[@]}" "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]')
    if [[ "$count" == "25" ]]; then
        pass "T3 read persists across restart"
    else
        fail "T3 read persists across restart" "expected 25, got '$count'"
    fi
fi

# ── T4 — write via DDL ──────────────────────────────────────────────────────
"${TRINO[@]}" "CREATE CATALOG tpch_dois USING tpch WITH (\"tpch.splits-per-node\" = '2')" >/dev/null 2>&1
row=$("${PSQL[@]}" "SELECT catalog_name || '|' || connector_name || '|' || updated_by || '|' || sync_status FROM trino_catalog_registry WHERE catalog_name = 'tpch_dois';")
if [[ "$row" == "tpch_dois|tpch|trino|synced" ]]; then
    pass "T4 CREATE CATALOG persists row"
else
    fail "T4 CREATE CATALOG persists row" "row='$row'"
fi

# ── T5 — DDL durable across restart ───────────────────────────────────────────
docker compose restart trino >/dev/null 2>&1
if ! wait_for_trino; then
    fail "T5 DDL persists across restart" "Trino did not respond within 60s after restart"
elif "${TRINO[@]}" "SHOW CATALOGS" 2>/dev/null | grep -qE '^[[:space:]]*tpch_dois[[:space:]]*$'; then
    pass "T5 DDL persists across restart"
else
    fail "T5 DDL persists across restart" "tpch_dois missing after restart"
fi

# ── T6 — removal ────────────────────────────────────────────────────────────
"${TRINO[@]}" "DROP CATALOG tpch_dois" >/dev/null 2>&1
enabled=$("${PSQL[@]}" "SELECT enabled FROM trino_catalog_registry WHERE catalog_name = 'tpch_dois';")
if [[ "$enabled" == "f" ]]; then
    pass "T6 DROP CATALOG soft-deletes"
else
    fail "T6 DROP CATALOG soft-deletes" "enabled='$enabled' (expected 'f')"
fi
docker compose restart trino >/dev/null 2>&1
if ! wait_for_trino; then
    fail "T6 dropped catalog stays dropped" "Trino did not respond within 60s after restart"
elif "${TRINO[@]}" "SHOW CATALOGS" 2>/dev/null | grep -qE '^[[:space:]]*tpch_dois[[:space:]]*$'; then
    fail "T6b dropped catalog stays dropped" "tpch_dois returned after restart"
else
    pass "T6b dropped catalog stays dropped"
fi

# ── T7 — corrupted row does not break boot ────────────────────────────────────
"${PSQL[@]}" "INSERT INTO trino_catalog_registry (cluster_id, catalog_name, connector_name, properties, updated_by)
VALUES ((SELECT id FROM trino_clusters WHERE name='default'),
        'quebrado', 'tpch', '{\"tpch.splits-per-node\": 4}'::jsonb, 'baleia')
ON CONFLICT (cluster_id, catalog_name) DO UPDATE
  SET properties = EXCLUDED.properties, enabled = true, sync_status = 'pending', sync_error = NULL;" >/dev/null 2>&1
ts=$(date -u +%Y-%m-%dT%H:%M:%S)
docker compose restart trino >/dev/null 2>&1
if ! wait_for_trino; then
    fail "T7 corrupted row skipped" "Trino did not respond within 60s after broken-row restart"
else
    if docker compose logs --since "$ts" trino 2>&1 | grep -q "Catalog 'quebrado' skipped"; then
        if "${TRINO[@]}" "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]' | grep -q 25; then
            status=$("${PSQL[@]}" "SELECT sync_status FROM trino_catalog_registry WHERE catalog_name = 'quebrado';")
            if [[ "$status" == "error" ]]; then
                pass "T7 corrupted row skipped, error marked, boot survives"
            else
                fail "T7 corrupted row skipped, error marked" "sync_status='$status' (expected 'error')"
            fi
        else
            fail "T7 corrupted row skipped" "tpch_teste is not queryable after broken-row restart"
        fi
    else
        fail "T7 corrupted row skipped" "no WARN log about 'quebrado'"
    fi
fi
# Cleanup the broken row.
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name = 'quebrado';" >/dev/null 2>&1

# ── T8 — secret does not leak ────────────────────────────────────────────────
# The placeholder syntax changed from ${baleia-secret:...}
# to @baleia-secret[...] because the Trino CLI interprets ${...} as a
# variable expansion before the SQL reaches our SecretResolver.
"${TRINO[@]}" "CREATE CATALOG seg USING tpch WITH (\"tpch.splits-per-node\" = '@baleia-secret[tpch_teste:tpch.splits-per-node]')" >/dev/null 2>&1

# T8a — the resolution actually worked (the catalog exists)
if "${TRINO[@]}" "SHOW CATALOGS" 2>/dev/null | grep -qE '^[[:space:]]*seg[[:space:]]*$'; then
    pass "T8a catalog created with resolved secret"
else
    fail "T8a catalog created with resolved secret" "catalog 'seg' missing — CREATE CATALOG failed"
fi

# T8b — the registry stores the REAL resolved value, never the placeholder
resolved_val=$("${PSQL[@]}" "SELECT properties->>'tpch.splits-per-node' FROM trino_catalog_registry WHERE catalog_name = 'seg';")
if [[ "$resolved_val" == "4" ]]; then
    pass "T8b registry stores the resolved value"
else
    fail "T8b registry stores the resolved value" "expected '4', got '$resolved_val'"
fi

# T8c — placeholder preserved in query log, resolved value NOT leaked
q=$("${TRINO[@]}" "SELECT query FROM system.runtime.queries WHERE query LIKE 'CREATE CATALOG seg USING%' ORDER BY query_id DESC LIMIT 1" 2>/dev/null)
if printf '%s' "$q" | grep -Fq '@baleia-secret[tpch_teste:tpch.splits-per-node]'; then
    if ! printf '%s' "$q" | grep -qE '"tpch\.splits-per-node"[[:space:]]*=[[:space:]]*'"'"'4'"'"''; then
        pass "T8c placeholder preserved in query log, resolved value not leaked"
    else
        fail "T8c secret not leaked" "query log shows resolved value '4' inline"
    fi
else
    fail "T8c secret not leaked" "placeholder not present in query log"
fi
# Best-effort cleanup: drop the seg catalog if it survived.
"${TRINO[@]}" "DROP CATALOG IF EXISTS seg" >/dev/null 2>&1 || true
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name = 'seg';" >/dev/null 2>&1

# ── T9 — reserved name rejected ─────────────────────────────────────────────────
if "${PSQL[@]}" "INSERT INTO trino_catalog_registry (cluster_id, catalog_name, connector_name, properties, updated_by)
VALUES ((SELECT id FROM trino_clusters WHERE name='default'), 'system', 'tpch', '{}'::jsonb, 'baleia');" >/dev/null 2>&1; then
    fail "T9 reserved name rejected at DB level" "insert succeeded"
else
    pass "T9 reserved name rejected at DB level"
fi

# ── Summary ──────────────────────────────────────────────────────────────────────
echo
echo "── Acceptance summary ──"
for r in "${RESULTS[@]}"; do echo "$r"; done
fails=$(printf '%s\n' "${RESULTS[@]}" | grep -c '^FAIL' || true)
echo
if (( fails == 0 )); then
    echo "All T1-T9 PASS." >&2
    exit 0
else
    echo "$fails FAIL(s)." >&2
    exit 1
fi