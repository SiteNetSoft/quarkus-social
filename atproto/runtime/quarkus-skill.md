# quarkus-social-atproto - skill notes for coding agents

Experimental. The AT Protocol (Bluesky) network of `quarkus-social`; it comes in by itself when the app has both
`quarkus-social` and `quarkus-atproto-oauth` (conditional dependency), or can be added directly.

- Needs `quarkus.atproto.oauth.mode=link`. In `login` mode it logs an error at startup and every post is
  `Skipped(NOT_LINKED, "quarkus.atproto.oauth.mode must be link")`.
- An app account publishes as the single DID it linked with quarkus-atproto's link flow, unless the app provides a
  `SocialAccountLinks` / `ReactiveSocialAccountLinks` bean. Several linked DIDs: `Failed(REJECTED)`.
- `quarkus.social.atproto.output`: `post-and-document` (default), `post` or `document`. NOTE -> `app.bsky.feed.post`
  with link and hashtag facets, over 300 graphemes `Failed(TEXT_TOO_LONG)`. ARTICLE -> a teaser post (title, blank
  line, summary or first paragraph, trimmed with "…") with a link card, then a `site.standard.document` whose
  `bskyPostRef` points at the post. `site` comes from a `StandardSitePublications` bean (default: the canonical URL's
  origin as `https://host[:port]`).
- Only `PUBLIC` posts; others are `Skipped(VISIBILITY_NOT_SUPPORTED)`. A content warning follows
  `quarkus.social.atproto.content-warning`: `skip` (default), `drop` or `label:<sexual|nudity|porn|graphic-media>`;
  `Post.atprotoLabels(...)` wins.
- Refs: `post.uri`, `post.cid`, `document.uri`, `document.cid`; `Published.url` from
  `quarkus.social.atproto.post-url-template`. Record keys are TIDs derived from the post: a retried publish never
  writes a second record.
- Edits update the document (`put` with swap on the stored CID); the post is kept (note "Bluesky post not edited")
  unless `quarkus.social.atproto.edit-post=recreate`. Deletes remove both records.
- The OAuth scope the output needs is logged at startup when `quarkus.atproto.oauth.scope` doesn't cover it (default
  output: `repo:app.bsky.feed.post?action=create&action=delete repo:site.standard.document?action=create&action=update&action=delete`).
- Errors: `SESSION_EXPIRED` (sign in again), `SCOPE_MISSING`, `UNAVAILABLE` (retryable), `REJECTED`.
