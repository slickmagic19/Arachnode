package com.arachnode.crawler;

import com.arachnode.model.CrawledPage;
import com.arachnode.model.LinkRef;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.security.MessageDigest;
import java.util.*;

/** Extracts SEO fields + issues from raw HTML. Mirrors SF's per-URL audit. */
public final class SeoAnalyzer {
    private SeoAnalyzer() {}

    public record Parsed(List<String> links, List<String> images, Document doc) {}

    /** Parse once per page — callers share the Document (no double parsing). */
    public static Document parse(String html, String pageUrl) {
        try {
            return Jsoup.parse(html, pageUrl);
        } catch (Exception e) {
            return null;
        }
    }

    public static void analyze(CrawledPage page, String html, String pageUrl, Document doc) {
        if (html == null || html.isEmpty()) {
            page.addIssue("Empty Body");
            page.rebuildIssueString();
            return;
        }
        if (doc == null) {
            page.addIssue("Parse Error");
            page.rebuildIssueString();
            return;
        }

        String title = doc.title() == null ? "" : doc.title().trim();
        page.setTitle(title);

        Element md = doc.selectFirst("meta[name=description]");
        page.setMetaDescription(md != null ? md.attr("content").trim() : "");

        Element kw = doc.selectFirst("meta[name=keywords]");
        page.setMetaKeywords(kw != null ? kw.attr("content").trim() : "");

        var h1s = doc.select("h1");
        page.setH1Count(h1s.size());
        page.setH1(h1s.isEmpty() ? "" : h1s.first().text().trim());
        page.setH2Count(doc.select("h2").size());

        Element can = doc.selectFirst("link[rel=canonical]");
        page.setCanonical(can != null ? can.attr("abs:href").trim() : "");

        Element robots = doc.selectFirst("meta[name=robots]");
        page.setMetaRobots(robots != null ? robots.attr("content").trim().toLowerCase() : "");

        var hreflangs = doc.select("link[rel=alternate][hreflang]");
        page.setHreflangCount(hreflangs.size());
        Set<String> langs = new LinkedHashSet<>();
        for (Element h : hreflangs) {
            String lang = h.attr("hreflang").trim().toLowerCase();
            if (!lang.isEmpty()) langs.add(lang);
        }
        page.setHreflangVals(String.join(", ", langs));

        // URL tab parts (SF parity): scheme / host / path / query.
        fillUrlParts(page, pageUrl);

        // Pagination tab: rel=prev / rel=next.
        Element prev = doc.selectFirst("link[rel=prev]");
        Element next = doc.selectFirst("link[rel=next]");
        page.setLinkPrev(prev != null ? prev.attr("abs:href").trim() : "");
        page.setLinkNext(next != null ? next.attr("abs:href").trim() : "");

        // JavaScript tab: external + inline scripts.
        var scripts = doc.select("script");
        int srcCount = 0;
        for (Element s : scripts) {
            if (!s.attr("src").trim().isEmpty()) srcCount++;
        }
        page.setScriptCount(scripts.size());
        page.setScriptSrcCount(srcCount);

        // AMP tab: link[rel=amphtml].
        Element amp = doc.selectFirst("link[rel=amphtml]");
        page.setAmpUrl(amp != null ? amp.attr("abs:href").trim() : "");

        // Structured Data tab: JSON-LD blocks.
        var ldBlocks = doc.select("script[type*=ld+json]");
        page.setJsonLdCount(ldBlocks.size());
        if (!ldBlocks.isEmpty()) {
            String first = ldBlocks.first().data().trim();
            if (first.length() > 4000) first = first.substring(0, 4000) + "\n… (truncated)";
            page.setFirstJsonLd(first);
        }

        String bodyText = doc.body() != null ? doc.body().text() : "";
        page.setWordCount(bodyText.isEmpty() ? 0 : bodyText.split("\\s+").length);

        var imgs = doc.select("img");
        page.setImageCount(imgs.size());
        int missingAlt = 0;
        page.getImageRefs().clear();
        for (Element img : imgs) {
            String alt = img.attr("alt");
            if (alt == null || alt.trim().isEmpty()) missingAlt++;
            if (page.getImageRefs().size() < 200) {
                String src = img.attr("abs:src").trim();
                if (src.isEmpty()) src = img.attr("src").trim();
                page.getImageRefs().add(new com.arachnode.model.ImageRef(src, alt == null ? "" : alt.trim()));
            }
        }
        page.setImagesMissingAlt(missingAlt);

        // Links tab: nofollow outlink split.
        int nofollow = 0;
        try {
            for (Element a : doc.select("a[rel]")) {
                if (a.attr("rel").toLowerCase().contains("nofollow")) nofollow++;
            }
        } catch (Exception ignored) {}
        page.setNofollowCount(nofollow);

        page.setContentHash(md5(html));
        page.setHtmlSnippet(html.length() > 2000 ? html.substring(0, 2000) : html);

        // ---- issues (SF-style thresholds) ----
        int code = page.getStatusCode();
        if (code >= 400 && code < 500) page.addIssue("Client Error " + code);
        if (code >= 500) page.addIssue("Server Error " + code);
        if (title.isEmpty()) page.addIssue("Missing Title");
        else {
            if (title.length() > 60) page.addIssue("Title Too Long (>60)");
            if (title.length() < 30) page.addIssue("Title Too Short (<30)");
        }
        String desc = page.getMetaDescription();
        if (desc.isEmpty()) page.addIssue("Missing Meta Description");
        else {
            if (desc.length() > 160) page.addIssue("Meta Description Too Long (>160)");
            if (desc.length() < 70) page.addIssue("Meta Description Too Short (<70)");
        }
        if (h1s.isEmpty()) page.addIssue("Missing H1");
        if (h1s.size() > 1) page.addIssue("Multiple H1 (" + h1s.size() + ")");
        if (missingAlt > 0) page.addIssue("Images Missing Alt (" + missingAlt + ")");
        if (page.getWordCount() < 200 && code == 200) page.addIssue("Thin Content (<200 words)");
        if (page.getUrl().length() > 115) page.addIssue("Long URL (>115)");
        if (page.getUrl().chars().filter(ch -> ch == '?').count() > 0
                || page.getUrl().contains("&")) page.addIssue("URL Has Parameters");
        if (!page.getMetaRobots().isEmpty()) {
            if (page.getMetaRobots().contains("noindex")) page.addIssue("Noindex");
            if (page.getMetaRobots().contains("nofollow")) page.addIssue("Nofollow");
        }
        if (!page.getXRobots().isEmpty()) {
            if (page.getXRobots().contains("noindex")) page.addIssue("X-Robots Noindex");
        }
        if (page.getCanonical().isEmpty() && code == 200) page.addIssue("Missing Canonical");
        else if (!page.getCanonical().isEmpty() && !page.getCanonical().equalsIgnoreCase(pageUrl))
            page.addIssue("Canonicalised");
        if (!page.getRedirectChain().isEmpty()) page.addIssue("Redirect: " + page.getRedirectChain());
        if (pageUrl.startsWith("http://")) page.addIssue("Non-Secure (HTTP)");
        if (doc.select("h2").isEmpty() && code == 200) page.addIssue("Missing H2");
        // Indexability (SF Directives parity)
        boolean noindex = page.getMetaRobots().contains("noindex")
                || (page.getXRobots() != null && page.getXRobots().contains("noindex"));
        page.setIndexable(noindex ? "Non-Indexable" : "Indexable");
        if (noindex && code == 200) { /* already flagged as Noindex above */ }
        page.rebuildIssueString();
    }

    /** All link instances with anchor text + rel (no re-parse: shares the Document). */
    public static List<LinkRef> extractLinks(Document doc) {
        return extractLinks(doc, "");
    }

    public static List<LinkRef> extractLinks(Document doc, String sourceUrl) {
        List<LinkRef> out = new ArrayList<>();
        if (doc == null) return out;
        try {
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href").trim();
                if (href.isEmpty()) continue;
                String anchor = a.text().trim().replaceAll("\\s+", " ");
                if (anchor.length() > 200) anchor = anchor.substring(0, 200);
                out.add(new LinkRef(sourceUrl == null ? "" : sourceUrl, href, anchor, a.attr("rel").trim().toLowerCase()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Parse scheme/host/path/query once — reused for every page including non-HTML. */
    public static void fillUrlParts(CrawledPage page, String pageUrl) {
        try {
            java.net.URI u = new java.net.URI(pageUrl);
            page.setUrlScheme(u.getScheme() == null ? "" : u.getScheme().toLowerCase());
            page.setUrlHost(u.getHost() == null ? "" : u.getHost().toLowerCase());
            page.setUrlPath(u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());
            page.setUrlQuery(u.getRawQuery() == null ? "" : u.getRawQuery());
        } catch (Exception ignored) {}
    }

    /** Page resources SF crawls as rows: CSS, JS, images, media, iframes (capped). */
    public record ResourceLink(String url, String kind) {}

    public static List<ResourceLink> extractResources(Document doc) {
        List<ResourceLink> out = new ArrayList<>();
        if (doc == null) return out;
        try {
            for (Element l : doc.select("link[href]")) {
                String rel = l.attr("rel").toLowerCase();
                if (rel.contains("stylesheet") || rel.contains("icon") || rel.contains("preload")
                        || rel.contains("preconnect") || rel.contains("manifest")) {
                    addAbs(out, l.attr("abs:href").trim(), rel.contains("stylesheet") ? "css" : "link");
                }
            }
            for (Element s : doc.select("script[src]")) addAbs(out, s.attr("abs:src").trim(), "js");
            for (Element i : doc.select("img[src]")) addAbs(out, i.attr("abs:src").trim(), "image");
            for (Element i : doc.select("img[srcset]")) addSrcset(out, i.attr("srcset"), doc.baseUri(), "image");
            for (Element s : doc.select("source[src]")) addAbs(out, s.attr("abs:src").trim(), "media");
            for (Element s : doc.select("source[srcset]")) addSrcset(out, s.attr("srcset"), doc.baseUri(), "media");
            for (Element v : doc.select("video[src]")) addAbs(out, v.attr("abs:src").trim(), "media");
            for (Element v : doc.select("video[poster]")) addAbs(out, v.attr("abs:poster").trim(), "image");
            for (Element a : doc.select("audio[src]")) addAbs(out, a.attr("abs:src").trim(), "media");
            for (Element e : doc.select("embed[src]")) addAbs(out, e.attr("abs:src").trim(), "media");
            for (Element f : doc.select("iframe[src]")) addAbs(out, f.attr("abs:src").trim(), "frame");
            if (out.size() > 300) return out.subList(0, 300);
        } catch (Exception ignored) {}
        return out;
    }

    private static void addAbs(List<ResourceLink> out, String href, String kind) {
        if (href == null || href.isEmpty()) return;
        if (href.startsWith("data:") || href.startsWith("blob:") || href.startsWith("javascript:")) return;
        out.add(new ResourceLink(href, kind));
    }

    private static void addSrcset(List<ResourceLink> out, String srcset, String base, String kind) {
        if (srcset == null || srcset.isEmpty()) return;
        try {
            for (String part : srcset.split(",")) {
                String token = part.trim().split("\\s+")[0].trim();
                if (token.isEmpty()) continue;
                String abs = new java.net.URI(base).resolve(token).toString();
                addAbs(out, abs, kind);
            }
        } catch (Exception ignored) {}
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
