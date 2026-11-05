package org.sitenetsoft.quarkus.social.content;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeVisitor;

/**
 * HTML allowlist sanitizing and text/HTML conversion. Everything the app or a remote server supplies as
 * HTML goes through {@link #sanitize(String)} before it is stored, sent or shown.
 */
public final class Html {

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "a", "strong", "em", "b", "i", "u", "s", "code", "pre", "blockquote",
                    "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "span")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener");

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n\\s*");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}'\"";
    private static final Set<String> BLOCKS = Set.of("p", "blockquote", "pre", "ul", "ol",
            "h1", "h2", "h3", "h4", "h5", "h6");

    private Html() {
    }

    /** Keeps only the allowlisted tags, {@code a[href]} with http, https or mailto, and forces {@code rel}. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", SAFELIST, settings);
    }

    /** Escapes plain text into paragraphs ({@code <p>}), line breaks ({@code <br>}) and links. */
    public static String fromText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        StringBuilder out = new StringBuilder();
        for (String paragraph : PARAGRAPH_BREAK.split(normalized)) {
            out.append("<p>");
            String[] lines = paragraph.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append("<br>");
                }
                appendLinked(out, lines[i]);
            }
            out.append("</p>");
        }
        return out.toString();
    }

    private static void appendLinked(StringBuilder out, String line) {
        Matcher m = URL.matcher(line);
        int last = 0;
        while (m.find()) {
            String url = m.group();
            int end = url.length();
            while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
                end--;
            }
            url = url.substring(0, end);
            if (url.length() <= "https://".length() && !url.matches("(?i)https?://.+")) {
                continue;
            }
            out.append(escape(line.substring(last, m.start())));
            String escaped = escape(url);
            out.append("<a href=\"").append(escaped).append("\" rel=\"nofollow noopener\">")
                    .append(escaped).append("</a>");
            last = m.start() + url.length();
        }
        out.append(escape(line.substring(last)));
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Plain text of the HTML: block elements and {@code <br>} become newlines, entities are decoded. */
    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        // Drop what is never text, even if the caller passed unsanitized markup.
        doc.select("script, style, template, head").remove();
        StringBuilder out = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    out.append(t.parent() instanceof Element e && e.normalName().equals("pre")
                            ? t.getWholeText() : t.text());
                } else if (node instanceof Element e) {
                    String name = e.normalName();
                    if (name.equals("br")) {
                        out.append('\n');
                    } else if (BLOCKS.contains(name)) {
                        blankLine(out);
                    } else if (name.equals("li")) {
                        newline(out);
                        out.append("- ");
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    blankLine(out);
                }
            }
        });
        return out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static void newline(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void blankLine(StringBuilder out) {
        if (out.isEmpty()) {
            return;
        }
        newline(out);
        if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') {
            out.append('\n');
        }
    }
}
