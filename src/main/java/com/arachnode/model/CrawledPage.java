package com.arachnode.model;

import java.util.ArrayList;
import java.util.List;

/** One fetched URL. Plain POJO so JavaFX PropertyValueFactory works. */
public class CrawledPage {
    private String url = "";
    private int statusCode = 0;
    private String statusText = "";
    private String contentType = "";
    private String title = "";
    private int titleLength = 0;
    private String metaDescription = "";
    private int metaDescLength = 0;
    private String h1 = "";
    private int h1Count = 0;
    private int h2Count = 0;
    private int wordCount = 0;
    private String canonical = "";
    private String metaRobots = "";
    private String xRobots = "";
    private int depth = 0;
    private int inlinks = 0;
    private int outlinks = 0;
    private int imageCount = 0;
    private int imagesMissingAlt = 0;
    private long responseTimeMs = 0;
    private long sizeBytes = 0;
    private String contentHash = "";
    private String redirectChain = "";
    private String issues = "";
    private String contentKind = "HTML"; // HTML, Image, CSS, JS, Other, Error

    // runtime-only (not shown in every column, used for details)
    private final List<LinkRef> outlinkRefs = new ArrayList<>();
    private final List<String> issueList = new ArrayList<>();
    private String htmlSnippet = "";

    public CrawledPage() {}
    public CrawledPage(String url, int depth) { this.url = url; this.depth = depth; }

    public String getUrl() { return url; }
    public void setUrl(String v) { url = v; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int v) { statusCode = v; }
    public String getStatusText() { return statusText; }
    public void setStatusText(String v) { statusText = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { contentType = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v == null ? "" : v; titleLength = title.length(); }
    public int getTitleLength() { return titleLength; }
    public String getMetaDescription() { return metaDescription; }
    public void setMetaDescription(String v) { metaDescription = v == null ? "" : v; metaDescLength = metaDescription.length(); }
    public int getMetaDescLength() { return metaDescLength; }
    public String getH1() { return h1; }
    public void setH1(String v) { h1 = v == null ? "" : v; }
    public int getH1Count() { return h1Count; }
    public void setH1Count(int v) { h1Count = v; }
    public int getH2Count() { return h2Count; }
    public void setH2Count(int v) { h2Count = v; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int v) { wordCount = v; }
    public String getCanonical() { return canonical; }
    public void setCanonical(String v) { canonical = v == null ? "" : v; }
    public String getMetaRobots() { return metaRobots; }
    public void setMetaRobots(String v) { metaRobots = v == null ? "" : v; }
    public String getXRobots() { return xRobots; }
    public void setXRobots(String v) { xRobots = v == null ? "" : v; }
    public int getDepth() { return depth; }
    public void setDepth(int v) { depth = v; }
    public int getInlinks() { return inlinks; }
    public void setInlinks(int v) { inlinks = v; }
    public int getOutlinks() { return outlinks; }
    public void setOutlinks(int v) { outlinks = v; }
    public int getImageCount() { return imageCount; }
    public void setImageCount(int v) { imageCount = v; }
    public int getImagesMissingAlt() { return imagesMissingAlt; }
    public void setImagesMissingAlt(int v) { imagesMissingAlt = v; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(long v) { responseTimeMs = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v == null ? "" : v; }
    public String getRedirectChain() { return redirectChain; }
    public void setRedirectChain(String v) { redirectChain = v == null ? "" : v; }
    public String getIssues() { return issues; }
    public void setIssues(String v) { issues = v == null ? "" : v; }
    public String getContentKind() { return contentKind; }
    public void setContentKind(String v) { contentKind = v; }

    public List<LinkRef> getOutlinkRefs() { return outlinkRefs; }
    public List<String> getIssueList() { return issueList; }
    public void addIssue(String i) { issueList.add(i); }
    public void rebuildIssueString() { issues = String.join("; ", issueList); }
    public String getHtmlSnippet() { return htmlSnippet; }
    public void setHtmlSnippet(String v) { htmlSnippet = v == null ? "" : v; }
}
