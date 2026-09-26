package com.arachnode.export;

import com.arachnode.model.CrawledPage;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** CSV export + XML sitemap generation (SF parity basics). */
public final class CrawlExporter {
    private CrawlExporter() {}

    public static void toCsv(List<CrawledPage> pages, Path file) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("URL,Status,Content Type,Title,Title Length,Meta Description,Meta Length,H1,H1 Count,H2 Count,Word Count,Canonical,Meta Robots,Depth,Inlinks,Outlinks,Images,Missing Alt,Response ms,Size,Issues");
            for (CrawledPage p : pages) {
                w.println(csv(p.getUrl()) + "," + p.getStatusCode() + "," + csv(p.getContentType()) + "," +
                        csv(p.getTitle()) + "," + p.getTitleLength() + "," + csv(p.getMetaDescription()) + "," +
                        p.getMetaDescLength() + "," + csv(p.getH1()) + "," + p.getH1Count() + "," + p.getH2Count() + "," +
                        p.getWordCount() + "," + csv(p.getCanonical()) + "," + csv(p.getMetaRobots()) + "," +
                        p.getDepth() + "," + p.getInlinks() + "," + p.getOutlinks() + "," + p.getImageCount() + "," +
                        p.getImagesMissingAlt() + "," + p.getResponseTimeMs() + "," + p.getSizeBytes() + "," + csv(p.getIssues()));
            }
        }
    }

    public static void toSitemap(List<CrawledPage> pages, Path file) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
            for (CrawledPage p : pages) {
                if (p.getStatusCode() != 200) continue;
                if (!p.getContentKind().equals("HTML")) continue;
                if (p.getIssues() != null && p.getIssues().contains("Noindex")) continue;
                w.println("  <url><loc>" + esc(p.getUrl()) + "</loc></url>");
            }
            w.println("</urlset>");
        }
    }

    private static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
