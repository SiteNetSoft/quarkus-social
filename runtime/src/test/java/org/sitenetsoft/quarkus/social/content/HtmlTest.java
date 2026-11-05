package org.sitenetsoft.quarkus.social.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.quarkus.social.Post;

class HtmlTest {

    @Test
    void stripsScriptAndHandlers() {
        assertEquals("<p>hi</p>",
                Html.sanitize("<p onclick=x>hi<script>x</script><img src=x onerror=y></p>"));
    }

    @Test
    void dropsJavascriptLinksAnyCase() {
        for (String href : new String[] {"javascript:alert(1)", "JaVaScRiPt:alert(1)",
                "javascript&colon;alert(1)", "  javascript:alert(1)", "\tjava\nscript:alert(1)",
                "data:text/html,x", "/relative"}) {
            String out = Html.sanitize("<a href=\"" + href + "\">x</a>");
            assertFalse(out.toLowerCase().contains("script:"), out);
            assertFalse(out.contains("href"), out);
            assertTrue(out.contains("x"), out);
        }
    }

    @Test
    void keepsHttpsAndMailto() {
        assertEquals("<a href=\"https://blog.example/a?x=1&amp;y=2\" rel=\"nofollow noopener\">t</a>",
                Html.sanitize("<a href=\"https://blog.example/a?x=1&y=2\">t</a>"));
        assertEquals("<a href=\"http://blog.example\" rel=\"nofollow noopener\">t</a>",
                Html.sanitize("<a href=\"http://blog.example\" target=\"_blank\" rel=\"me\">t</a>"));
        assertEquals("<a href=\"mailto:alice@blog.example\" rel=\"nofollow noopener\">m</a>",
                Html.sanitize("<a href=\"mailto:alice@blog.example\">m</a>"));
    }

    @Test
    void dropsStyle() {
        assertEquals("<p>x</p>", Html.sanitize("<style>p{}</style><p style=\"color:red\" class=\"c\" id=\"i\">x</p>"));
    }

    @Test
    void keepsAllowedTags() {
        String in = "<h1>a</h1><p>b <strong>c</strong><em>d</em><b>e</b><i>f</i><u>g</u><s>h</s><code>i</code><br>"
                + "<span>j</span></p><pre>k</pre><blockquote>l</blockquote><ul><li>m</li></ul><ol><li>n</li></ol><h6>o</h6>";
        assertEquals(in, Html.sanitize(in));
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertEquals("", Html.sanitize(null));
        assertEquals("", Html.fromText(null));
        assertEquals("", Html.toText(null));
    }

    @Test
    void fromTextEscapesAndLinks() {
        assertEquals("<p>a &lt; b</p><p><a href=\"https://blog.example\" rel=\"nofollow noopener\">https://blog.example</a></p>",
                Html.fromText("a < b\n\nhttps://blog.example"));
    }

    @Test
    void fromTextLineBreaksAndTrailingPunctuation() {
        assertEquals("<p>one<br>two (<a href=\"http://blog.example/x?a=1&amp;b=2\" rel=\"nofollow noopener\">"
                + "http://blog.example/x?a=1&amp;b=2</a>).</p>",
                Html.fromText("one\r\ntwo (http://blog.example/x?a=1&b=2)."));
    }

    @Test
    void fromTextNeverProducesMarkupFromInput() {
        assertEquals("<p>&lt;script&gt;x&lt;/script&gt;</p>", Html.fromText("<script>x</script>"));
        assertEquals(Html.fromText("<b>x</b>"), Html.sanitize(Html.fromText("<b>x</b>")));
    }

    @Test
    void toTextRoundTrip() {
        String text = "a < b & c\n\nhttps://blog.example";
        assertEquals(text, Html.toText(Html.fromText(text)));
        assertEquals("one\ntwo", Html.toText("<p>one<br>two</p>"));
        assertEquals("a\n\nb", Html.toText("<p>a</p><p>b</p>"));
        assertEquals("T\n\n- x\n- y", Html.toText("<h1>T</h1><ul><li>x</li><li>y</li></ul>"));
        assertEquals("see the docs (https://blog.example) & more",
                Html.toText("<p>see <a href=\"https://blog.example\">the docs</a> &amp; more</p>"));
    }

    @Test
    void toTextKeepsLinkAddresses() {
        // a link showing its address stays as it is
        assertEquals("go https://blog.example/x now",
                Html.toText("<p>go <a href=\"https://blog.example/x\">https://blog.example/x</a> now</p>"));
        assertEquals("mail alice@blog.example",
                Html.toText("<p>mail <a href=\"mailto:alice@blog.example\">alice@blog.example</a></p>"));
        assertEquals("mail me (mailto:alice@blog.example)",
                Html.toText("<p>mail <a href=\"mailto:alice@blog.example\">me</a></p>"));
        assertEquals("https://blog.example/empty", Html.toText("<a href=\"https://blog.example/empty\"></a>"));
        // other schemes (unsanitized input) are not shown
        assertEquals("click", Html.toText("<a href=\"javascript:alert(1)\">click</a>"));
        // an HTML-only post keeps its links in the text the text-only networks get
        PostContent c = PostContent.of(Post.builder("l1")
                .html("<p>Read <a href=\"https://blog.example/post?a=1&amp;b=2\">my post</a>.</p>").build());
        assertEquals("Read my post (https://blog.example/post?a=1&b=2).", c.text());
    }

    @Test
    void toTextNeverReturnsMarkup() {
        String out = Html.toText("<p>x<script>alert(1)</script><img src=x onerror=y></p>");
        assertEquals("x", out);
        assertFalse(Html.toText("&amp;lt;b&amp;gt;").contains("&amp;"));
    }

    @Test
    void postContentPrefersHtml() {
        Post p = Post.builder("1").html("<p onclick=x>hi</p>").build();
        PostContent c = PostContent.of(p);
        assertEquals("<p>hi</p>", c.html());
        assertEquals("hi", c.text());

        Post both = Post.builder("2").html("<p>h</p>").text("plain").build();
        assertEquals("<p>h</p>", PostContent.of(both).html());
        assertEquals("plain", PostContent.of(both).text());

        Post textOnly = Post.builder("3").text("a & b").build();
        assertEquals("<p>a &amp; b</p>", PostContent.of(textOnly).html());
        assertEquals("a & b", PostContent.of(textOnly).text());
    }
}
