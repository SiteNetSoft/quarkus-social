package org.sitenetsoft.quarkus.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostTest {

    @Test
    void defaults() {
        Post p = Post.builder("p1").text("hello").build();
        assertEquals(Kind.NOTE, p.kind());
        assertEquals(Visibility.PUBLIC, p.visibility());
    }

    @Test
    void articleNeedsTitleAndUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> Post.builder("p").kind(Kind.ARTICLE).text("x").url(URI.create("https://blog.example/a")).build());
        assertThrows(IllegalArgumentException.class,
                () -> Post.builder("p").kind(Kind.ARTICLE).text("x").title("T").build());
        Post.builder("p").kind(Kind.ARTICLE).text("x").title("T").url(URI.create("https://blog.example/a")).build();
    }

    @Test
    void needsTextOrHtml() {
        assertThrows(IllegalArgumentException.class, () -> Post.builder("p").build());
        assertThrows(IllegalArgumentException.class, () -> Post.builder("p").text("  ").build());
        Post.builder("p").html("<p>x</p>").build();
    }

    @Test
    void rejectsJavascriptUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> Post.builder("p").text("x").url(URI.create("javascript:alert(1)")).build());
        assertThrows(IllegalArgumentException.class,
                () -> Post.builder("p").text("x").url(URI.create("ftp://blog.example/a")).build());
    }

    @Test
    void rejectsTagWithHash() {
        assertThrows(IllegalArgumentException.class, () -> Post.builder("p").text("x").tags(List.of("#quarkus")).build());
        assertThrows(IllegalArgumentException.class, () -> Post.builder("p").text("x").tags(List.of("a b")).build());
    }

    @Test
    void rejectsLongId() {
        assertThrows(IllegalArgumentException.class, () -> Post.builder("a".repeat(257)).text("x").build());
        assertThrows(IllegalArgumentException.class, () -> Post.builder(" ").text("x").build());
        Post.builder("a".repeat(256)).text("x").build();
    }
}
