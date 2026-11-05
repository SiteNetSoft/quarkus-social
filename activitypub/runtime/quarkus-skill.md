# quarkus-social-activitypub - skill notes for coding agents

Experimental. The ActivityPub network of `quarkus-social`; it comes in by itself when the app has both
`quarkus-social` and `quarkus-activitypub` (conditional dependency), or can be added directly.

- An app account publishes as the local actor of the same username (`LocalActors.register`), unless the app
  provides a `SocialAccountLinks` / `ReactiveSocialAccountLinks` bean. Unknown account: `Skipped(NOT_LINKED)`.
- `NOTE` -> `Note`; `ARTICLE` -> `Note` with title, teaser and link (default), or `Article` with
  `quarkus.social.activitypub.article-style=article`. `PUBLIC` to Public cc followers, `UNLISTED` to followers cc
  Public, `FOLLOWERS` to followers. Tags -> `Hashtag` + `#tag` links; content warning -> `sensitive` + `summary`.
- `Published` means queued for delivery; the note says `queued for N inboxes, M failed at once`. Refs: `object`
  (also the url), `activity` (the Create), `activity.update.<n>`, `activity.delete`.
- Delivery counts arrive later as `SocialDeliveryProgress` events and in `social.status(postId)`; `pending == 0`
  means finished.
- The object id is derived from the post id: a retried publish never creates a second object.
- `quarkus.social.activitypub.enabled=false` answers every call `Skipped(DISABLED_BY_CONFIG)`.
