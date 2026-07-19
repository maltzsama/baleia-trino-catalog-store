#!/usr/bin/env bash
# Runs T1-T9 from CR-015 rev.2 §6 against a running `docker compose up -d` stack.
# Each step prints PASS/FAIL and the table at the end summarizes the result.
#
# Usage:
#   cd docker && ./acceptance.sh
#
# Preconditions:
#   - the compose stack is up (postgres + trino)
#   - PGPASSWORD for the baleia superuser is exported (default: baleia)

set -u

PGPASSWORD="${PGPASSWORD:-baleia}"
PSQL=(psql -h localhost -p 5432 -U baleia -d baleia -t -A -v ON_ERROR_STOP=1)
TRINO=(docker compose exec -T trino trino --execute)

declare -a RESULTS=()
pass() { RESULTS+=("PASS  $1"); }
fail() { RESULTS+=("FAIL  $1 -> $2"); }

# Reset the seed row to its known state so re-runs are reproducible.
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = true, sync_status = 'pending', sync_error = NULL WHERE catalog_name = 'tpch_teste';" >/dev/null 2>&1
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name IN ('tpch_dois', 'seg');" >/dev/null 2>&1

# ── T1 — read on boot ────────────────────────────────────────────────────
if docker compose exec -T trino trino --execute "SHOW CATALOGS" 2>/dev/null | grep -q '^tpch_teste$'; then
    pass "T1 read on boot"
else
    fail "T1 read on boot" "tpch_teste not in SHOW CATALOGS"
fi

# ── T2 — catalog actually works ────────────────────────────────────────────────
count=$(docker compose exec -T trino trino --execute "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]')
if [[ "$count" == "25" ]]; then
    pass "T2 catalog is queryable"
else
    fail "T2 catalog is queryable" "expected 25, got '$count'"
fi

# ── T3 — persists across restart ──────────────────────────────────────────────────
docker compose restart trino >/dev/null 2>&1
for _ in {1..30}; do
    if docker compose exec -T trino trino --execute "SELECT 1" 2>/dev/null | grep -q 1; then break; fi
    sleep 1
done
count=$(docker compose exec -T trino trino --execute "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]')
if [[ "$count" == "25" ]]; then
    pass "T3 read persists across restart"
else
    fail "T3 read persists across restart" "expected 25, got '$count'"
fi

# ── T4 — write via DDL ───────────────────────────────────────────────────────────
docker compose exec -T trino trino --execute \
    "CREATE CATALOG tpch_dois USING tpch WITH (\"tpch.splits-per-node\" = '2')" >/dev/null 2>&1
row=$("${PSQL[@]}" "SELECT catalog_name || '|' || connector_name || '|' || updated_by || '|' || sync_status FROM trino_catalog_registry WHERE catalog_name = 'tpch_dois';")
if [[ "$row" == "tpch_dois|tpch|trino|synced" ]]; then
    pass "T4 CREATE CATALOG persists row"
else
    fail "T4 CREATE CATALOG persists row" "row='$row'"
fi

# ── T5 — DDL durable across restart ──────────────────────────────────────────────
docker compose restart trino >/dev/null 2>&1
for _ in {1..30}; do
    if docker compose exec -T trino trino --execute "SELECT 1" 2>/dev/null | grep -q 1; then break; fi
    sleep 1
done
if docker compose exec -T trino trino --execute "SHOW CATALOGS" 2>/dev/null | grep -q '^tpch_dois$'; then
    pass "T5 DDL persists across restart"
else
    fail "T5 DDL persists across restart" "tpch_dois missing after restart"
fi

# ── T6 — removal ─────────────────────────────────────────────────────────────────
docker compose exec -T trino trino --execute "DROP CATALOG tpch_dois" >/dev/null 2>&1
enabled=$("${PSQL[@]}" "SELECT enabled FROM trino_catalog_registry WHERE catalog_name = 'tpch_dois';")
if [[ "$enabled" == "f" ]]; then
    pass "T6 DROP CATALOG soft-deletes"
else
    fail "T6 DROP CATALOG soft-deletes" "enabled='$enabled' (expected 'f')"
fi
docker compose restart trino >/dev/null 2>&1
for _ in {1..30}; do
    if docker compose exec -T trino trino --execute "SELECT 1" 2>/dev/null | grep -q 1; then break; fi
    sleep 1
done
if docker compose exec -T trino trino --execute "SHOW CATALOGS" 2>/dev/null | grep -q '^tpch_dois$'; then
    fail "T6 dropped catalog stays dropped" "tpch_dois returned after restart"
else
    pass "T6 dropped catalog stays dropped"
fi

# ── T7 — corrupted row does not break boot ──────────────────────────────────────
"${PSQL[@]}" "INSERT INTO trino_catalog_registry (cluster_id, catalog_name, connector_name, properties, updated_by)
VALUES ((SELECT id FROM trino_clusters WHERE name='default'),
        'quebrado', 'tpch', '{\"tpch.splits-per-node\": 4}'::jsonb, 'baleia')
ON CONFLICT (cluster_id, catalog_name) DO UPDATE
  SET properties = EXCLUDED.properties, enabled = true, sync_status = 'pending', sync_error = NULL;" >/dev/null 2>&1
docker compose restart trino >/dev/null 2>&1
for _ in {1..30}; do
    if docker compose exec -T trino trino --execute "SELECT 1" 2>/dev/null | grep -q 1; then break; fi
    sleep 1
done
if docker compose logs trino 2>&1 | grep -q "Catalog 'quebrado' skipped"; then
    if docker compose exec -T trino trino --execute "SELECT count(*) FROM tpch_teste.tiny.nation" 2>/dev/null | tr -d '[:space:]' | grep -q 25; then
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
# Cleanup the broken row.
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name = 'quebrado';" >/dev/null 2>&1

# ── T8 — secret does not leak ────────────────────────────────────────────────────
docker compose exec -T trino trino --execute \
    "CREATE CATALOG seg USING tpch WITH (\"tpch.splits-per-node\" = '\${baleia-secret:tpch_teste:tpch.splits-per-node}')" >/dev/null 2>&1
q=$(docker compose exec -T trino trino --execute \
    "SELECT query FROM system.runtime.queries WHERE query LIKE '%CREATE CATALOG seg%' ORDER BY query_id DESC LIMIT 1" 2>/dev/null)
# Two checks from CR-015 rev.2 §6 T8:
#   1. query text contains the ${baleia-secret:catalog:key} placeholder (verbatim).
#   2. query text does NOT contain the resolved value in the "key = 'value'" shape.
if echo "$q" | grep -qF '${baleia-secret:tpch_teste:tpch.splits-per-node}'; then
    if ! echo "$q" | grep -qE 'tpch\.splits-per-node"\s*=\s*'"'"'4'"'"''; then
        pass "T8 placeholder preserved in query log, resolved value not leaked"
    else
        fail "T8 secret not leaked" "query log shows resolved value '4' inline"
    fi
else
    fail "T8 secret not leaked" "placeholder not present in query log"
fi
# Best-effort cleanup: drop the seg catalog if it survived.
docker compose exec -T trino trino --execute "DROP CATALOG seg" >/dev/null 2>&1 || true
"${PSQL[@]}" "UPDATE trino_catalog_registry SET enabled = false WHERE catalog_name = 'seg';" >/dev/null 2>&1

# ── T9 — reserved name rejected ────────────────────────────────────────────────
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