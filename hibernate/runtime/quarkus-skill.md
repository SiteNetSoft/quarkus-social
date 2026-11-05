# quarkus-social-hibernate - skill notes for coding agents

Experimental. Keeps the publish state of `quarkus-social` in your database, so a restart or a second node does not
publish a post twice.

- Add `quarkus-social-hibernate` plus a JDBC driver and set `quarkus.datasource.db-kind`. `HibernateSocialPostStore`
  then replaces the in-memory default; no code. Check: the `SocialPostStore` bean is a `HibernateSocialPostStore`.
- Tables (default persistence unit): `social_post`, `social_network_state`, `social_ref`. No migrations are shipped.
  Tests: `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`. Production: create the tables with your
  migration tool and use `validate`.
- Every store call runs in its own new transaction (`QuarkusTransaction.requiringNew()`), also when the caller has
  one. The rows record what was already sent to Mastodon and Bluesky, so an application rollback must not erase them,
  and a lost compare-and-set must not mark the caller's transaction rollback-only.
- `replace` is a compare-and-set on `social_post.version` (`@Version`). `social_ref` is rewritten with the network
  states, so a ref that no longer exists stops resolving to the post.
- `social_ref` is not unique: posts may share a ref value (lookup by network + SHA-256 of the ref, then exact compare). The engine's reserved `_url` ref is not indexed. A ref over 1024 characters fails the write.
- Do not write the `social_*` tables yourself, and do not call the store from the event loop.
