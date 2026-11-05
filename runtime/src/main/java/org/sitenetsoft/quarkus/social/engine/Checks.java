package org.sitenetsoft.quarkus.social.engine;

import org.sitenetsoft.quarkus.social.Post;
import org.sitenetsoft.quarkus.social.SocialException;
import org.sitenetsoft.quarkus.social.content.PostContent;
import org.sitenetsoft.quarkus.social.store.NetworkState.Status;
import org.sitenetsoft.quarkus.social.store.StoredPost;

/** The caller errors of publish, update and delete, as {@link SocialException}s. */
final class Checks {

    private static final String NEW_POST = "delete it and publish a new post";

    private Checks() {
    }

    /** A repeated publish: same owner, same kind and visibility, and never after a delete. */
    static void checkRepeat(StoredPost stored, String account, Post post) {
        checkOwner(stored, account);
        checkCarriable(stored, post);
        if (stored.networks().values().stream().anyMatch(s -> s.status() == Status.DELETED)) {
            throw new SocialException("post " + post.id() + " was deleted; publish it under a new id");
        }
    }

    static void requireAccount(String account) {
        if (account == null || account.isBlank()) {
            throw new SocialException("account must not be blank");
        }
    }

    static void requirePost(Post post) {
        if (post == null) {
            throw new SocialException("post must not be null");
        }
    }

    /** Html that sanitizes to nothing (only a {@code <script>}, say) and no text leaves nothing to publish. */
    static void requireContent(Post post) {
        if (PostContent.of(post).text().isBlank()) {
            throw new SocialException("post " + post.id() + " has no content after sanitizing");
        }
    }

    static void checkOwner(StoredPost stored, String account) {
        if (!stored.account().equals(account)) {
            throw new SocialException("post " + stored.postId() + " belongs to another account");
        }
    }

    /** Kind and visibility can't change in place on every network, so the post must be replaced instead. */
    static void checkCarriable(StoredPost stored, Post post) {
        if (stored.kind() != post.kind()) {
            throw new SocialException("post " + stored.postId() + ": the kind can't change from " + stored.kind()
                    + " to " + post.kind() + "; " + NEW_POST);
        }
        if (stored.visibility() != post.visibility()) {
            throw new SocialException("post " + stored.postId() + ": the visibility can't change from "
                    + stored.visibility() + " to " + post.visibility() + "; " + NEW_POST);
        }
    }
}
