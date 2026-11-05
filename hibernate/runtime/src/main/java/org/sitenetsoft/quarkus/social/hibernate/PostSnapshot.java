package org.sitenetsoft.quarkus.social.hibernate;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.sitenetsoft.quarkus.social.Kind;
import org.sitenetsoft.quarkus.social.Network;
import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.Visibility;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The JSON form of a {@link Post}: plain strings, lists and booleans, so it needs no Jackson modules and reads back
 * the same in any {@code ObjectMapper}. {@code networks} is null when the post has no network restriction.
 */
@RegisterForReflection
record PostSnapshot(String id, String kind, String title, String summary, String text, String html, String url,
        String language, List<String> tags, String visibility, boolean sensitive, String contentWarning,
        String publishedAt, List<String> networks, List<String> atprotoLabels) {

    static PostSnapshot of(Post post) {
        return new PostSnapshot(post.id(), post.kind().name(), post.title().orElse(null), post.summary().orElse(null),
                post.text().orElse(null), post.html().orElse(null), post.url().map(URI::toString).orElse(null),
                post.language().orElse(null), post.tags(), post.visibility().name(), post.sensitive(),
                post.contentWarning().orElse(null), post.publishedAt().toString(),
                post.networks().map(set -> set.stream().map(Network::id).sorted().toList()).orElse(null),
                post.atprotoLabels());
    }

    Post toPost() {
        Post.Builder builder = Post.builder(id).kind(Kind.valueOf(kind)).title(title).summary(summary).text(text)
                .html(html).url(url == null ? null : URI.create(url)).language(language)
                .tags(tags == null ? List.of() : tags).visibility(Visibility.valueOf(visibility))
                .sensitive(sensitive).contentWarning(contentWarning).publishedAt(Instant.parse(publishedAt))
                .atprotoLabels(atprotoLabels == null ? List.of() : atprotoLabels);
        if (networks != null) {
            builder.networks(networks.stream().map(Network::new).collect(Collectors.toCollection(HashSet::new)));
        }
        return builder.build();
    }
}
