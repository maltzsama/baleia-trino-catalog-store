#!/usr/bin/env bash
# Two distinct booleans control this image's boot, and they are NOT the same
# thing (confirmed the hard way against a real deployment):
#
# - TRINO_IS_COORDINATOR: whether THIS container is the coordinator process
#   at all (Trino's own `coordinator` property). Always "true" for the
#   coordinator Deployment, always "false" for the worker Deployment. Only
#   `catalog.store` is coordinator-only — confirmed empirically that a pure
#   worker (coordinator=false) rejects it outright at boot ("Configuration
#   property 'catalog.store' was not used"). `catalog.management=dynamic`
#   is NOT coordinator-only despite looking like it should be: every node
#   needs it so it can accept catalogs the coordinator pushes at task
#   dispatch time. A worker booted without it defaults to the static
#   catalog manager and rejects that push with "Missing catalogs: [...]" —
#   also reproduced against a real cluster.
# - TRINO_INCLUDE_COORDINATOR: Trino's own `node-scheduler.include-coordinator`
#   — whether the coordinator ALSO executes query tasks itself (single-node
#   topology) vs. delegating all tasks to separate workers (cluster
#   topology). This is deepdiver's ChartTrinoProvisioner#coordinator_env
#   convention, patched onto the coordinator Deployment based on
#   TrinoEngineConfig.cluster? — it says nothing about which Deployment IS
#   the coordinator. Conflating the two (treating
#   TRINO_INCLUDE_COORDINATOR=false as "this container is a worker") makes
#   the coordinator Deployment itself boot reporting coordinator:false,
#   which breaks every /v1/statement call with a 404 — reproduced against a
#   real cluster.
#
# Airlift's ${ENV:NAME} substitution can't conditionally omit a line, so
# this entrypoint writes config.properties at boot instead of shipping a
# static one.
set -euo pipefail

IS_COORDINATOR="${TRINO_IS_COORDINATOR:-true}"
INCLUDE_COORDINATOR="${TRINO_INCLUDE_COORDINATOR:-true}"
CONFIG_FILE=/etc/trino/config.properties

{
  echo "coordinator=${IS_COORDINATOR}"
  if [ "$IS_COORDINATOR" = "true" ]; then
    echo "node-scheduler.include-coordinator=${INCLUDE_COORDINATOR}"
    echo "catalog.store=baleia"
  fi
  # catalog.management=dynamic is NOT coordinator-only: in dynamic mode the
  # coordinator pushes resolved catalog definitions to every node at task
  # dispatch time, and a node booted with the default static manager rejects
  # that push outright (StaticCatalogManager#ensureCatalogsLoaded throws
  # "Missing catalogs: [...]"). Confirmed against a real cluster: the
  # coordinator loaded catalog 'ibe' successfully, but every worker task
  # against it failed with exactly that exception until this line stopped
  # being conditional on IS_COORDINATOR. Only catalog.store is genuinely
  # coordinator-only (a pure worker rejects it at boot: "Configuration
  # property 'catalog.store' was not used").
  echo "catalog.management=dynamic"
  echo "http-server.http.port=8080"
  echo "discovery.uri=${TRINO_DISCOVERY_URI}"
} > "$CONFIG_FILE"

exec /usr/lib/trino/bin/run-trino
