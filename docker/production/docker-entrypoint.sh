#!/usr/bin/env bash
# catalog.management/catalog.store are coordinator-only properties — a pure
# worker (coordinator=false) rejects them at boot with "Configuration
# property 'catalog.store' was not used", confirmed empirically. Airlift's
# ${ENV:NAME} substitution can't conditionally omit a line, so this
# entrypoint writes the coordinator-only lines only when
# TRINO_INCLUDE_COORDINATOR=true (the deepdiver/ChartTrinoProvisioner
# convention, not an upstream Trino env var) before handing off to the base
# image's own entrypoint.
set -euo pipefail

INCLUDE_COORDINATOR="${TRINO_INCLUDE_COORDINATOR:-true}"
CONFIG_FILE=/etc/trino/config.properties

{
  echo "coordinator=${INCLUDE_COORDINATOR}"
  echo "node-scheduler.include-coordinator=${INCLUDE_COORDINATOR}"
  echo "http-server.http.port=8080"
  echo "discovery.uri=${TRINO_DISCOVERY_URI}"
  if [ "$INCLUDE_COORDINATOR" = "true" ]; then
    echo "catalog.management=dynamic"
    echo "catalog.store=baleia"
  fi
} > "$CONFIG_FILE"

exec /usr/lib/trino/bin/run-trino
