package org.sitenetsoft.quarkus.social;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A protocol-neutral post. Build with {@link #builder(String)}. */
public final class Post {

    private static final int MAX_ID = 256;

    private final String id;
    private final Kind kind;
    private final Optional<String> title;
    private final Optional<String> summary;
    private final Optional<String> text;
    private final Optional<String> html;
    private final Optional<URI> url;
    private final Optional<String> language;
    private final List<String> tags;
    private final Visibility visibility;
    private final boolean sensitive;
    private final Optional<String> contentWarning;
    private final Instant publishedAt;
    private final Optional<Set<Network>> networks;
    private final List<String> atprotoLabels;

    private Post(Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.title = Optional.ofNullable(b.title);
        this.summary = Optional.ofNullable(b.summary);
        this.text = Optional.ofNullable(b.text);
        this.html = Optional.ofNullable(b.html);
        this.url = Optional.ofNullable(b.url);
        this.language = Optional.ofNullable(b.language);
        this.tags = List.copyOf(b.tags);
        this.visibility = b.visibility;
        this.sensitive = b.sensitive;
        this.contentWarning = Optional.ofNullable(b.contentWarning);
        this.publishedAt = b.publishedAt;
        this.networks = Optional.ofNullable(b.networks);
        this.atprotoLabels = List.copyOf(b.atprotoLabels);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public Optional<String> title() { return title; }
    public Optional<String> summary() { return summary; }
    public Optional<String> text() { return text; }
    public Optional<String> html() { return html; }
    public Optional<URI> url() { return url; }
    public Optional<String> language() { return language; }
    public List<String> tags() { return tags; }
    public Visibility visibility() { return visibility; }
    public boolean sensitive() { return sensitive; }
    public Optional<String> contentWarning() { return contentWarning; }
    public Instant publishedAt() { return publishedAt; }
    public Optional<Set<Network>> networks() { return networks; }
    public List<String> atprotoLabels() { return atprotoLabels; }

    @Override
    public String toString() {
        return "Post[" + id + ", " + kind + "]";
    }

    public static final class Builder {
        private final String id;
        private Kind kind = Kind.NOTE;
        private String title;
        private String summary;
        private String text;
        private String html;
        private URI url;
        private String language;
        private List<String> tags = List.of();
        private Visibility visibility = Visibility.PUBLIC;
        private boolean sensitive;
        private String contentWarning;
        private Instant publishedAt = Instant.now();
        private Set<Network> networks;
        private List<String> atprotoLabels = List.of();

        private Builder(String id) {
            this.id = id;
        }

        public Builder kind(Kind kind) { this.kind = kind; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder summary(String summary) { this.summary = summary; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder html(String html) { this.html = html; return this; }
        public Builder url(URI url) { this.url = url; return this; }
        public Builder language(String language) { this.language = language; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder visibility(Visibility visibility) { this.visibility = visibility; return this; }
        public Builder sensitive(boolean sensitive) { this.sensitive = sensitive; return this; }
        public Builder contentWarning(String contentWarning) { this.contentWarning = contentWarning; return this; }
        public Builder publishedAt(Instant publishedAt) { this.publishedAt = publishedAt; return this; }
        public Builder networks(Set<Network> networks) { this.networks = networks == null ? null : Set.copyOf(networks); return this; }
        public Builder atprotoLabels(List<String> labels) { this.atprotoLabels = labels; return this; }

        public Post build() {
            if (id == null || id.isBlank() || id.length() > MAX_ID) {
                throw new IllegalArgumentException("post id must be non-blank and at most " + MAX_ID + " characters");
            }
            if (kind == null || visibility == null || publishedAt == null || tags == null || atprotoLabels == null) {
                throw new IllegalArgumentException("kind, visibility, publishedAt, tags and atprotoLabels must not be null");
            }
            if (blank(text) && blank(html)) {
                throw new IllegalArgumentException("a post needs text or html");
            }
            if (kind == Kind.ARTICLE && (blank(title) || url == null)) {
                throw new IllegalArgumentException("an ARTICLE needs a title and a url");
            }
            if (url != null) {
                String scheme = url.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    throw new IllegalArgumentException("url must be http or https: " + url);
                }
            }
            for (String tag : tags) {
                if (tag == null || tag.isEmpty() || tag.indexOf('#') >= 0 || tag.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("a tag must not be empty or contain whitespace or '#': " + tag);
                }
            }
            return new Post(this);
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }
}
