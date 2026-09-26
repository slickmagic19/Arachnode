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

        var h1s = doc.select("h1");
        page.setH1Count(h1s.size());
        page.setH1(h1s.isEmpty() ? "" : h1s.first().text().trim());
        page.setH2Count(doc.select("h2").size());

        Element can = doc.selectFirst("link[rel=canonical]");
        page.setCanonical(can != null ? can.attr("abs:href").trim() : "");

        Element robots = doc.selectFirst("meta[name=robots]");
        page.setMetaRobots(robots != null ? robots.attr("content").trim().toLowerCase() : "");

        String bodyText = doc.body() != null ? doc.body().text() : "";
        page.setWordCount(bodyText.isEmpty() ? 0 : bodyText.split("\\s+").length);

        var imgs = doc.select("img");
        page.setImageCount(imgs.size());
        int missingAlt = 0;
        for (Element img : imgs) {
            String alt = img.attr("alt");
            if (alt == null || alt.trim().isEmpty()) missingAlt++;
        }
        page.setImagesMissingAlt(missingAlt);

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
        if (!page.getRedirectChain().isEmpty()) page.addIssue("Redirect: " + page.getRedirectChain());
        page.rebuildIssueString();
    }

    /** All link instances with anchor text + rel (no re-parse: shares the Document). */
    public static List<LinkRef> extractLinks(Document doc) {
        List<LinkRef> out = new ArrayList<>();
        if (doc == null) return out;
        try {
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href").trim();
                if (href.isEmpty()) continue;
                String anchor = a.text().trim().replaceAll("\\s+", " ");
                if (anchor.length() > 200) anchor = anchor.substring(0, 200);
                out.add(new LinkRef(href, anchor, a.attr("rel").trim().toLowerCase()));
            }
        } catch (Exception ignored) {}
        return out;
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
