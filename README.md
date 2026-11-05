# quarkus-social

A protocol-neutral Quarkus extension: `social.publish(account, post)` sends one post to Mastodon
(ActivityPub) and Bluesky (AT Protocol) and returns one result per network. The neutral API never
exposes a protocol type.

Status: experimental.

This repository currently holds the repo skeleton and the neutral model (`Post`, `Network`,
`PublishResult`, `NetworkResult`, `Capabilities`). The engine, adapters, persistence, Dev UI and a
sample app are added in later steps.

## Build order

The sibling repositories must be built and installed first, in this order:

`quarkus-safe-fetch` -> `quarkus-webfinger` -> `quarkus-http-signatures` -> `quarkus-activitypub` ->
`multikey-java` -> `quarkus-did` -> `quarkus-atproto` -> `quarkus-social`

Requires Java 21 and Quarkus 3.40.1. Licensed under Apache 2.0.
