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
