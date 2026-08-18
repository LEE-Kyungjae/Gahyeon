package com.gahyeonbot.services.news;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Component
public class NewsFeedParser {
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    public List<ParsedArticle> parse(byte[] xml, PersonalizedNewsProperties.Source source) {
        if (xml == null || xml.length == 0) return List.of();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            List<ParsedArticle> result = new ArrayList<>();
            collect(document, "item", source, result);
            collect(document, "entry", source, result);
            return result;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid RSS/Atom feed", error);
        }
    }

    private void collect(Document document, String tag, PersonalizedNewsProperties.Source source,
                         List<ParsedArticle> result) {
        NodeList nodes = document.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() == 0) nodes = document.getElementsByTagName(tag);
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String title = text(element, "title");
            String rawUrl = link(element);
            OffsetDateTime publishedAt = date(element);
            if (title == null || rawUrl == null || publishedAt == null) continue;
            String canonicalUrl = canonicalizeUrl(rawUrl);
            URI uri = URI.create(canonicalUrl);
            String domain = IDN.toUnicode(uri.getHost() == null ? "" : uri.getHost()).toLowerCase(Locale.ROOT);
            result.add(new ParsedArticle(
                    canonicalUrl, source.getId(), source.getName(), domain,
                    source.isOfficial(), source.getTrustTier(), title.strip(),
                    firstNonBlank(text(element, "description"), text(element, "summary"), text(element, "content")),
                    publishedAt, fingerprint(title)));
        }
    }

    public String canonicalizeUrl(String raw) {
        URI uri = URI.create(raw.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("news URL must use http(s)");
        }
        String query = uri.getRawQuery();
        List<String> kept = new ArrayList<>();
        if (query != null) {
            for (String part : query.split("&")) {
                String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (!key.startsWith("utm_") && !TRACKING_PARAMETERS.contains(key)) kept.add(part);
            }
        }
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getUserInfo(),
                    uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath(), kept.isEmpty() ? null : String.join("&", kept), null).toASCIIString();
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid news URL", error);
        }
    }

    public String fingerprint(String title) {
        TreeSet<String> tokens = new TreeSet<>();
        Arrays.stream(TOKEN_SPLIT.split(title.toLowerCase(Locale.ROOT)))
                .map(String::strip).filter(token -> token.length() >= 2).limit(16).forEach(tokens::add);
        String normalized = String.join(" ", tokens);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String link(Element element) {
        NodeList links = element.getElementsByTagNameNS("*", "link");
        if (links.getLength() == 0) links = element.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Node node = links.item(i);
            if (node instanceof Element link && link.hasAttribute("href")) return link.getAttribute("href");
            String value = node.getTextContent();
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private OffsetDateTime date(Element element) {
        for (String tag : List.of("pubDate", "published", "updated", "date")) {
            String value = text(element, tag);
            if (value == null) continue;
            try { return OffsetDateTime.parse(value); } catch (Exception ignored) { }
            try { return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME); } catch (Exception ignored) { }
            try { return java.time.Instant.parse(value).atOffset(ZoneOffset.UTC); } catch (Exception ignored) { }
        }
        return null;
    }

    private String text(Element element, String tag) {
        NodeList nodes = element.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() == 0) nodes = element.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    public record ParsedArticle(String canonicalUrl, String sourceId, String sourceName, String sourceDomain,
                                boolean official, int trustTier, String title, String summary,
                                OffsetDateTime publishedAt, String eventFingerprint) { }
}
