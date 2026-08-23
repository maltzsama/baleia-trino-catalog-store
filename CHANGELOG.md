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

## [0.2.1](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.2.0...v0.2.1) (2026-08-10)


### Bug Fixes

* align docs, config and env with current dependency versions ([bcfba04](https://github.com/maltzsama/baleia-trino-catalog-store/commit/bcfba044fe78cd0ea3d2193587e257fcb7f463b2))

# [0.2.0](https://github.com/maltzsama/baleia-trino-catalog-store/compare/v0.1.0...v0.2.0) (2026-08-10)


### Bug Fixes

* don't override transitive hibernate-validator version ([4a95dde](https://github.com/maltzsama/baleia-trino-catalog-store/commit/4a95dde291a17b7ff1a31f5927b1946c0df80e44))
* split jackson version for annotations vs databind ([0fffdc6](https://github.com/maltzsama/baleia-trino-catalog-store/commit/0fffdc60cdfe02e22b47d7ca8df340bc7142d79a))


### Features

* make DB retry and backoff configurable ([5955fda](https://github.com/maltzsama/baleia-trino-catalog-store/commit/5955fda6d902530d647c3d6d376323068b204ade))
