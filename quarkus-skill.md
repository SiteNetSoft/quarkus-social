# quarkus-social - skill notes for coding agents

Experimental. `social.publish(account, post)` will send one post to Mastodon (ActivityPub) and Bluesky
(AT Protocol) and return one result per network. This skeleton holds only the protocol-free model
(`Post`, `Network`, `PublishResult`, `NetworkResult`, `Capabilities`); the engine and adapters follow.
