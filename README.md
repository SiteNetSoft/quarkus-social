# quarkus-social

A protocol-neutral Quarkus extension: `social.publish(account, post)` sends one post to Mastodon
(ActivityPub) and Bluesky (AT Protocol) and returns one result per network. The neutral API never
exposes a protocol type.

Status: experimental.

Modules: `quarkus-social` (neutral API, publish engine, Dev UI card), `quarkus-social-activitypub`,
`quarkus-social-atproto`, `quarkus-social-hibernate` (persistent publish state) and `quarkus-social-test`. The
documentation is in `docs/` (Antora): getting started, results and retry, each network, interactions, storage and
the manual live check.

## Build order

The sibling repositories must be built and installed first, in this order:

`quarkus-safe-fetch` -> `quarkus-webfinger` -> `quarkus-http-signatures` -> `quarkus-activitypub` ->
`multikey-java` -> `quarkus-did` -> `quarkus-atproto` -> `quarkus-social`

Requires Java 21 and Quarkus 3.40.1. Licensed under Apache 2.0.
