## [0.4.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.4.0...v0.4.1) (2026-08-22)


### Bug Fixes

* add git config to gh-pages deploy step ([1ef6a98](https://github.com/maltzsama/baleia-trino-catalog-store/commit/1ef6a989582bf86cb090c038a089cf44ad81a437)), closes [#pages](https://github.com/maltzsama/baleia-trino-catalog-store/issues/pages)
* start Docker stack before acceptance tests in CI ([dd18972](https://github.com/maltzsama/baleia-trino-catalog-store/commit/dd1897225e4562f5a2f28b34c0d0818c23a42637))
* wait for Trino to be ready before first acceptance test ([8dddbbc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/8dddbbcae38fa432846855e5bf8e6e719deb3a9d))

# [0.4.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.3.0...v0.4.0) (2026-08-22)


### Bug Fixes

* added metals e bloop to gitignore ([2570c9a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/2570c9a2378fc07230990cb9e819b2ae868be6dd))


### Features

* support Trino 482 and 483 with single artifact ([bf07d2a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bf07d2a80690f2cafc82888ea3ed6a57ce6aa65e))

# [0.3.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.2.1...v0.3.0) (2026-08-22)


### Bug Fixes

* added dump.sh to ignore ([c467475](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c46747542e69bec260e4482eef85a27271841e38))


### Features

* add file: and env: secret resolution schemes ([55954bc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/55954bcb6e025e6f29977f51b16142d44ea12141))

## [0.9.2](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.9.1...v0.9.2) (2026-10-17)


### Bug Fixes

* **store:** evict pending catalog properties left by a failed CREATE ([#31](https://github.com/maltzsama/baleia-trino-catalog-store/issues/31)) ([9041b9b](https://github.com/maltzsama/baleia-trino-catalog-store/commit/9041b9b0e1a520716329b3955d25b4ff1db90d3b))
* **store:** evict pending catalog properties left by a failed CREATE ([#31](https://github.com/maltzsama/baleia-trino-catalog-store/issues/31)) ([28ac220](https://github.com/maltzsama/baleia-trino-catalog-store/commit/28ac2205346861ceb7812fddca1e80aa60389e5c))

## [0.9.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.9.0...v0.9.1) (2026-10-15)


### Bug Fixes

* **secrets:** keep self-referenced secrets in the row, so the catalog survives a restart ([a218a03](https://github.com/maltzsama/baleia-trino-catalog-store/commit/a218a03dbc967af7036efd1cf8de597608401eb9))
* **secrets:** keep self-referenced secrets in the row, so the catalog survives a restart ([28a4a62](https://github.com/maltzsama/baleia-trino-catalog-store/commit/28a4a623518fcc589896a9fb545b20319201dbb0))

## [0.9.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.8.0...v0.9.0) (2026-10-15)


### Features

* **contract:** shared contract fixtures in contract/, shipped in the release tarball ([4144bd9](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4144bd91523446402e4d3167319fb45b575b3d32))
* **contract:** shared contract fixtures in contract/, shipped in the release tarball ([f6def28](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f6def28839ce56256f4ac9e268e89f3d57c9b923))

## [0.8.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.7.0...v0.8.0) (2026-10-14)


### Features

* drop Trino 482 support, target 483 only ([371bb31](https://github.com/maltzsama/baleia-trino-catalog-store/commit/371bb31b3bc775b69a93bb6ba44e286c6b906d94))
* drop Trino 482 support, target 483 only ([0db06ee](https://github.com/maltzsama/baleia-trino-catalog-store/commit/0db06eec2bd3a7583cc441837d0ebc951146e606))


### Bug Fixes

* **secrets:** harden file: scheme and clear stale sync errors ([b9c7d9b](https://github.com/maltzsama/baleia-trino-catalog-store/commit/b9c7d9b5b97c4a7488a98654ea36794d554529ae))
* **secrets:** harden file: scheme and clear stale sync errors ([3365067](https://github.com/maltzsama/baleia-trino-catalog-store/commit/33650671ddfa0e5056da2567d84ba85a72ad6f33))
* **secrets:** keep the newer pending entry when rejecting a stale version ([afdfdb2](https://github.com/maltzsama/baleia-trino-catalog-store/commit/afdfdb25055ac7cb02b950b5e7e2a8c6ee919758))
* **secrets:** persist [@baleia-secret](https://github.com/baleia-secret) placeholders, never the resolved values ([70b3c68](https://github.com/maltzsama/baleia-trino-catalog-store/commit/70b3c68fdbcd41485d12f4ff7a580d9a8361fd9b))
* **secrets:** persist [@baleia-secret](https://github.com/baleia-secret) placeholders, never the resolved values ([7489db1](https://github.com/maltzsama/baleia-trino-catalog-store/commit/7489db149330fb1615f2e0cf7ae7abdd9dfff6ee))
* **secrets:** rename clearErrors to markSynced to satisfy SpotBugs ([f1eab42](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f1eab424f6b7251164186cdc897ab8b8f399095d))

## [0.7.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.6.1...v0.7.0) (2026-10-13)


### Features

* **guard:** catalog management authorization, read-only mode, admin identities ([9f6cc7b](https://github.com/maltzsama/baleia-trino-catalog-store/commit/9f6cc7b96342e51ef399930da35248b22790c5e2))
* **guard:** catalog management authorization, read-only mode, admin identities ([1f814cd](https://github.com/maltzsama/baleia-trino-catalog-store/commit/1f814cd4c48e5e28205c72310f699504431fe24b))


### Bug Fixes

* **guard:** allow what it does not decide, and let an unauthenticated admin connect ([ae7e80b](https://github.com/maltzsama/baleia-trino-catalog-store/commit/ae7e80bf5f722622af36ea0b507df443d77728c2))
* **guard:** allow what it does not decide, and let an unauthenticated admin connect ([abc0984](https://github.com/maltzsama/baleia-trino-catalog-store/commit/abc09846c39887c8ce86353254bce969aed3c698))

## [0.6.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.6.0...v0.6.1) (2026-08-31)


### Bug Fixes

* catalog.management=dynamic is not coordinator-only, unlike catalog.store ([aac52e2](https://github.com/maltzsama/baleia-trino-catalog-store/commit/aac52e25e291e06fbcfe7b464593cd62ac3ce5a7))

## [0.6.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.5.4...v0.6.0) (2026-08-31)


### Features

* add file: and env: secret resolution schemes ([f0321bc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f0321bc2c4c2090e98d03683b08a97ee4728334b))
* add file: and env: secret resolution schemes ([55954bc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/55954bcb6e025e6f29977f51b16142d44ea12141))
* make DB retry and backoff configurable ([5955fda](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5955fda6d902530d647c3d6d376323068b204ade))
* support Trino 482 and 483 with single artifact ([bf07d2a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bf07d2a80690f2cafc82888ea3ed6a57ce6aa65e))


### Bug Fixes

* add git config to gh-pages deploy step ([1ef6a98](https://github.com/maltzsama/baleia-trino-catalog-store/commit/1ef6a989582bf86cb090c038a089cf44ad81a437))
* added dump.sh to ignore ([c467475](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c46747542e69bec260e4482eef85a27271841e38))
* added metals e bloop to gitignore ([2570c9a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/2570c9a2378fc07230990cb9e819b2ae868be6dd))
* align docs, config and env with current dependency versions ([bcfba04](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bcfba044fe78cd0ea3d2193587e257fcb7f463b2))
* **ci:** add distributionManagement so deploy actually uploads ([#39](https://github.com/maltzsama/baleia-trino-catalog-store/issues/39)) ([fce8212](https://github.com/maltzsama/baleia-trino-catalog-store/commit/fce8212df7ab6e4118b4dc4415a36ec65b0c4a60))
* create target dir before pandoc conversion ([ca6c972](https://github.com/maltzsama/baleia-trino-catalog-store/commit/ca6c972b31464498d4f6ce3f8ac80263a9856b6b))
* create target dir before pandoc conversion ([9b3f9de](https://github.com/maltzsama/baleia-trino-catalog-store/commit/9b3f9def56a52218ddc238bf27026ef84ae5055f))
* don't override transitive hibernate-validator version ([4a95dde](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4a95dde291a17b7ff1a31f5927b1946c0df80e44))
* remove colon from release-please package name ([83277f5](https://github.com/maltzsama/baleia-trino-catalog-store/commit/83277f5a8110473c5dce97f072c9ac078c174d4a))
* remove package-name from release-please config to use standard v&lt;version&gt; tags ([c73bf6d](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c73bf6db343a978f57200c8f98cb3340be9c5190))
* replace text blocks and use Java 25 for Jitpack compatibility ([5d1861a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5d1861a8c00c0a84138bf1586035b69672af0e7e))
* set admin password for dev catalog-store ([d121056](https://github.com/maltzsama/baleia-trino-catalog-store/commit/d12105672828ee7a294fa48aabb97a4b880a9c0c))
* split jackson version for annotations vs databind ([0fffdc6](https://github.com/maltzsama/baleia-trino-catalog-store/commit/0fffdc60cdfe02e22b47d7ca8df340bc7142d79a))
* start Docker stack before acceptance tests in CI ([dd18972](https://github.com/maltzsama/baleia-trino-catalog-store/commit/dd1897225e4562f5a2f28b34c0d0818c23a42637))
* use actions/deploy-pages for gh-pages publication ([774cd71](https://github.com/maltzsama/baleia-trino-catalog-store/commit/774cd71c9cbea0fc2ccb21d9c4ccfd8a447b47a6))
* wait for Trino to be ready before first acceptance test ([8dddbbc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/8dddbbcae38fa432846855e5bf8e6e719deb3a9d))


### Documentation

* Docs:  ([f225a26](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f225a26f6b2ffe74ee725cfa594816113e4dff92))
* add Backend requirements section and clarify 01-schema.sql as dev bootstrap ([4d5d13f](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4d5d13f46a6ab08bfd2d97cb6c387a7ce21729ed))
* add README as Javadoc overview via pandoc in CI ([062672d](https://github.com/maltzsama/baleia-trino-catalog-store/commit/062672d73d6d9bf4cd0eadda4b6cb5ffa8783864))
* document quality gates, new config and retry knobs ([d29c658](https://github.com/maltzsama/baleia-trino-catalog-store/commit/d29c6588e2004f5f20cf4a3a380f6e863353a990))
* improve Javadoc and deploy to gh-pages ([c814db5](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c814db578ca35f8ac0b10c5cf6ca08acc7de8a71))
* Javadoc with README overview, tracking, and gh-pages deploy ([fdd1f85](https://github.com/maltzsama/baleia-trino-catalog-store/commit/fdd1f85cdbeb7d0e09a7c5e6e95b0be4496f78c0))

## [0.5.2](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.5.1...v0.5.2) (2026-08-31)


### Bug Fixes

* **ci:** add distributionManagement so deploy actually uploads ([#39](https://github.com/maltzsama/baleia-trino-catalog-store/issues/39)) ([fce8212](https://github.com/maltzsama/baleia-trino-catalog-store/commit/fce8212df7ab6e4118b4dc4415a36ec65b0c4a60))

## [0.5.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.5.0...v0.5.1) (2026-08-31)


### Bug Fixes

* remove package-name from release-please config to use standard v&lt;version&gt; tags ([c73bf6d](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c73bf6db343a978f57200c8f98cb3340be9c5190))

## [0.5.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/baleia-trino-catalog-store-v0.4.1...baleia-trino-catalog-store-v0.5.0) (2026-08-24)


### Features

* add file: and env: secret resolution schemes ([f0321bc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f0321bc2c4c2090e98d03683b08a97ee4728334b))
* add file: and env: secret resolution schemes ([55954bc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/55954bcb6e025e6f29977f51b16142d44ea12141))
* make DB retry and backoff configurable ([5955fda](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5955fda6d902530d647c3d6d376323068b204ade))
* support Trino 482 and 483 with single artifact ([bf07d2a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bf07d2a80690f2cafc82888ea3ed6a57ce6aa65e))


### Bug Fixes

* add git config to gh-pages deploy step ([1ef6a98](https://github.com/maltzsama/baleia-trino-catalog-store/commit/1ef6a989582bf86cb090c038a089cf44ad81a437))
* added dump.sh to ignore ([c467475](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c46747542e69bec260e4482eef85a27271841e38))
* added metals e bloop to gitignore ([2570c9a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/2570c9a2378fc07230990cb9e819b2ae868be6dd))
* align docs, config and env with current dependency versions ([bcfba04](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bcfba044fe78cd0ea3d2193587e257fcb7f463b2))
* create target dir before pandoc conversion ([ca6c972](https://github.com/maltzsama/baleia-trino-catalog-store/commit/ca6c972b31464498d4f6ce3f8ac80263a9856b6b))
* create target dir before pandoc conversion ([9b3f9de](https://github.com/maltzsama/baleia-trino-catalog-store/commit/9b3f9def56a52218ddc238bf27026ef84ae5055f))
* don't override transitive hibernate-validator version ([4a95dde](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4a95dde291a17b7ff1a31f5927b1946c0df80e44))
* remove colon from release-please package name ([83277f5](https://github.com/maltzsama/baleia-trino-catalog-store/commit/83277f5a8110473c5dce97f072c9ac078c174d4a))
* replace text blocks and use Java 25 for Jitpack compatibility ([5d1861a](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5d1861a8c00c0a84138bf1586035b69672af0e7e))
* set admin password for dev catalog-store ([d121056](https://github.com/maltzsama/baleia-trino-catalog-store/commit/d12105672828ee7a294fa48aabb97a4b880a9c0c))
* split jackson version for annotations vs databind ([0fffdc6](https://github.com/maltzsama/baleia-trino-catalog-store/commit/0fffdc60cdfe02e22b47d7ca8df340bc7142d79a))
* start Docker stack before acceptance tests in CI ([dd18972](https://github.com/maltzsama/baleia-trino-catalog-store/commit/dd1897225e4562f5a2f28b34c0d0818c23a42637))
* use actions/deploy-pages for gh-pages publication ([774cd71](https://github.com/maltzsama/baleia-trino-catalog-store/commit/774cd71c9cbea0fc2ccb21d9c4ccfd8a447b47a6))
* wait for Trino to be ready before first acceptance test ([8dddbbc](https://github.com/maltzsama/baleia-trino-catalog-store/commit/8dddbbcae38fa432846855e5bf8e6e719deb3a9d))


### Documentation

* Docs:  ([f225a26](https://github.com/maltzsama/baleia-trino-catalog-store/commit/f225a26f6b2ffe74ee725cfa594816113e4dff92))
* add Backend requirements section and clarify 01-schema.sql as dev bootstrap ([4d5d13f](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4d5d13f46a6ab08bfd2d97cb6c387a7ce21729ed))
* add README as Javadoc overview via pandoc in CI ([062672d](https://github.com/maltzsama/baleia-trino-catalog-store/commit/062672d73d6d9bf4cd0eadda4b6cb5ffa8783864))
* document quality gates, new config and retry knobs ([d29c658](https://github.com/maltzsama/baleia-trino-catalog-store/commit/d29c6588e2004f5f20cf4a3a380f6e863353a990))
* improve Javadoc and deploy to gh-pages ([c814db5](https://github.com/maltzsama/baleia-trino-catalog-store/commit/c814db578ca35f8ac0b10c5cf6ca08acc7de8a71))
* Javadoc with README overview, tracking, and gh-pages deploy ([fdd1f85](https://github.com/maltzsama/baleia-trino-catalog-store/commit/fdd1f85cdbeb7d0e09a7c5e6e95b0be4496f78c0))

## [0.2.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.2.0...v0.2.1) (2026-08-10)


### Bug Fixes

* align docs, config and env with current dependency versions ([bcfba04](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bcfba044fe78cd0ea3d2193587e257fcb7f463b2))

# [0.2.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.1.0...v0.2.0) (2026-08-10)


### Bug Fixes

* don't override transitive hibernate-validator version ([4a95dde](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4a95dde291a17b7ff1a31f5927b1946c0df80e44))
* split jackson version for annotations vs databind ([0fffdc6](https://github.com/maltzsama/baleia-trino-catalog-store/commit/0fffdc60cdfe02e22b47d7ca8df340bc7142d79a))


### Features

* make DB retry and backoff configurable ([5955fda](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5955fda6d902530d647c3d6d376323068b204ade))
