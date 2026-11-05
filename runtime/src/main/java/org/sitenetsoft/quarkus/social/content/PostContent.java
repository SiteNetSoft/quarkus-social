package org.sitenetsoft.quarkus.social.content;

import org.sitenetsoft.quarkus.social.Post;

/** The sanitized HTML and the plain text of a post, as handed to the adapters. */
public record PostContent(String html, String text) {

    /** Html wins when present (sanitized); otherwise the HTML is derived from the text. */
    public static PostContent of(Post post) {
        if (post.html().filter(h -> !h.isBlank()).isPresent()) {
            String html = Html.sanitize(post.html().get());
            String text = post.text().filter(t -> !t.isBlank()).orElseGet(() -> Html.toText(html));
            return new PostContent(html, text);
        }
        String text = post.text().orElse("");
        return new PostContent(Html.fromText(text), text);
    }
}
