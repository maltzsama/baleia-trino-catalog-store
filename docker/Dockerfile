# Builds a Trino image with the baleia-trino-catalog-store plugin installed,
# for real (non-dev) deployments — the docker-compose.yml in this directory
# mounts a local Maven build instead, which only works for local dev.
#
# Build from the repo root:
#   docker build -f docker/Dockerfile -t <registry>/baleia-trino:TAG .
#
# TRINO_VERSION must match (or be within) this plugin's supported window —
# see the table in README.md ("Supported Trino versions").
ARG TRINO_VERSION=482

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
COPY src ./src
# -DskipTests: acceptance/unit tests need the docker-compose stack (Postgres,
# a running Trino) that does not exist at image-build time.
RUN ./mvnw -B package -DskipTests

FROM trinodb/trino:${TRINO_VERSION}

# etc/catalog must stay empty — catalog.management=dynamic (config.properties
# below) means Trino gets every catalog from the plugin, not static files.
# The base image ships no files there by default; this is a guard against a
# future base-image change silently reintroducing one.
RUN rm -rf /etc/trino/catalog/* 2>/dev/null || true

COPY --from=build /build/target/baleia-catalog-store /usr/lib/trino/plugin/baleia-catalog-store
COPY docker/production/catalog-store.properties /etc/trino/catalog-store.properties
COPY --chmod=755 docker/production/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# jvm.config and node.properties are NOT overridden — see the comment in
# docker/docker-compose.yml explaining why (the base image's versions carry
# JDK 25 runtime flags a hand-written file would silently drop).

# config.properties is NOT copied in — the entrypoint below writes it at
# boot, since catalog.management/catalog.store are coordinator-only
# properties and must be omitted entirely on a pure worker (confirmed:
# Trino rejects catalog.store outright when coordinator=false, it isn't
# something Airlift's ${ENV:...} substitution can conditionally blank out).
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
