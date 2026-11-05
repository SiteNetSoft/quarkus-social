# quarkus-social - skill notes for coding agents

Experimental. `social.publish(account, post)` sends one post to every active network (Mastodon through
`quarkus-social-activitypub`, Bluesky through `quarkus-social-atproto`) and returns one `NetworkResult` per network:
`Published`, `Skipped(reason)` or `Failed(code, retryable)`. The neutral API knows no protocol type. Blocking
`Social` and Mutiny `ReactiveSocial` share one engine; the blocking calls throw on the event loop.

- `account` is the app's own account id; the post id is the app's stable id (used by update, delete, retry, status).
  `Post.builder(id)` needs `text` or `html`; an `ARTICLE` also needs `title` and `url`.
- Never call `publish`/`update`/`delete` inside an app transaction (remote calls; dev mode warns once). Publish after
  commit. The store writes in its own transactions.
- A network problem is a `Failed` result, never an exception. `SocialException` means a caller error: unknown post,
  another account's post, a visibility or kind change on update, a deleted post id, every network already having the
  post (on `publish`), no adapter.
- A repeated `publish` of the same id retries the STORED post on the networks that don't have it yet (the passed
  content is ignored); content changes need `update`. Visibility and kind changes are refused on update: delete and
  publish under a new id. A deleted post id can't be published again.
- Several adapters are found with `@All List<SocialAdapter>`. A per-network lease (`Failed(IN_PROGRESS)`, retryable)
  stops two nodes sending the same post at once. ActivityPub delivery itself is single-node.
- SPIs with `@DefaultBean` defaults: `SocialPostStore`/`ReactiveSocialPostStore` (in-memory: warns in production,
  state lost on restart; use `quarkus-social-hibernate`), `SocialAccountLinks`/`ReactiveSocialAccountLinks` (no module
  ships a bean; the adapters have internal defaults and use the app's bean when present).
- Settings: `quarkus.social.timeout` (`30s`); the adapters' are in their skill notes.
- Events (`SocialReply`, `SocialReaction`, `SocialReactionUndone`, `SocialFollow`, `SocialDeliveryProgress`) fire after
  the inbox transaction commits: observers that write to the database use `@Transactional(REQUIRES_NEW)`. Render only
  `ReplyAudience.PUBLIC` and `UNLISTED` replies publicly. Only ActivityPub delivers interactions (Bluesky: Milestone 5).
- Dev UI card "Social" (dev mode): adapters and capabilities, adapter contributions (`SocialDevUiContribution` beans,
  e.g. the Bluesky scope needed vs configured), the last 20 posts, a dev-mode-only "publish a test post" form.
- Tests: `quarkus-social-test` has `RecordingSocialAdapter` (subclass it as a bean) and `SocialAssertions`.
