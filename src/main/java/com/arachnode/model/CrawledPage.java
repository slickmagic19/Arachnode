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
    private String redirectUri = "";
    private String metaKeywords = "";
    private int hreflangCount = 0;
    private String hreflangVals = "";
    private String indexable = "Indexable";
    private String issues = "";
    private String contentKind = "HTML"; // HTML, Image, CSS, JS, Other, Error, Redirect, External
    // URL tab (SF parity): parsed components of the URL.
    private String urlScheme = "";
    private String urlHost = "";
    private String urlPath = "";
    private String urlQuery = "";
    // Pagination tab: rel=prev / rel=next link targets.
    private String linkPrev = "";
    private String linkNext = "";
    // JavaScript tab: external + inline script counts.
    private int scriptCount = 0;
    private int scriptSrcCount = 0;
    // AMP tab: link[rel=amphtml] target.
    private String ampUrl = "";
    // Structured Data tab: JSON-LD block count.
    private int jsonLdCount = 0;
    // Links tab: follow vs nofollow outlink split.
    private int nofollowCount = 0;
    // HTTP Headers tab: server + content-length response headers.
    private String serverHeader = "";
    private String contentLength = "";
    // Cookies tab: Set-Cookie response headers (joined, truncated).
    private String cookies = "";
    // Structured Data Details tab: first JSON-LD block (truncated).
    private String firstJsonLd = "";
    // Custom Search tab: per-query match counts, recomputed when the tab is shown.
    private String customMatches = "";
    // Diagnostics: fetch attempts, protocol, and full error for failed fetches.
    private int fetchAttempts = 0;
    private String fetchProto = "";
    private String errorDetail = "";

    // runtime-only (not shown in every column, used for details)
    private final List<LinkRef> outlinkRefs = new ArrayList<>();
    private final List<ImageRef> imageRefs = new ArrayList<>();
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
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String v) { redirectUri = v == null ? "" : v; }
    public String getMetaKeywords() { return metaKeywords; }
    public void setMetaKeywords(String v) { metaKeywords = v == null ? "" : v; }
    public int getHreflangCount() { return hreflangCount; }
    public void setHreflangCount(int v) { hreflangCount = v; }
    public String getHreflangVals() { return hreflangVals; }
    public void setHreflangVals(String v) { hreflangVals = v == null ? "" : v; }
    public String getUrlScheme() { return urlScheme; }
    public void setUrlScheme(String v) { urlScheme = v == null ? "" : v; }
    public String getUrlHost() { return urlHost; }
    public void setUrlHost(String v) { urlHost = v == null ? "" : v; }
    public String getUrlPath() { return urlPath; }
    public void setUrlPath(String v) { urlPath = v == null ? "" : v; }
    public String getUrlQuery() { return urlQuery; }
    public void setUrlQuery(String v) { urlQuery = v == null ? "" : v; }
    public int getUrlLength() { return url == null ? 0 : url.length(); }
    public String getLinkPrev() { return linkPrev; }
    public void setLinkPrev(String v) { linkPrev = v == null ? "" : v; }
    public String getLinkNext() { return linkNext; }
    public void setLinkNext(String v) { linkNext = v == null ? "" : v; }
    public int getScriptCount() { return scriptCount; }
    public void setScriptCount(int v) { scriptCount = v; }
    public int getScriptSrcCount() { return scriptSrcCount; }
    public void setScriptSrcCount(int v) { scriptSrcCount = v; }
    public String getAmpUrl() { return ampUrl; }
    public void setAmpUrl(String v) { ampUrl = v == null ? "" : v; }
    public int getJsonLdCount() { return jsonLdCount; }
    public void setJsonLdCount(int v) { jsonLdCount = v; }
    public int getNofollowCount() { return nofollowCount; }
    public void setNofollowCount(int v) { nofollowCount = v; }
    public int getFollowCount() { return Math.max(0, outlinks - nofollowCount); }
    public String getServerHeader() { return serverHeader; }
    public void setServerHeader(String v) { serverHeader = v == null ? "" : v; }
    public String getContentLength() { return contentLength; }
    public void setContentLength(String v) { contentLength = v == null ? "" : v; }
    public String getCookies() { return cookies; }
    public void setCookies(String v) { cookies = v == null ? "" : v; }
    public int getCookieCount() {
        if (cookies.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < cookies.length(); i++) if (cookies.charAt(i) == '\n') n++;
        return n;
    }
    public String getFirstJsonLd() { return firstJsonLd; }
    public void setFirstJsonLd(String v) { firstJsonLd = v == null ? "" : v; }
    public String getCustomMatches() { return customMatches; }
    public void setCustomMatches(String v) { customMatches = v == null ? "" : v; }
    public int getFetchAttempts() { return fetchAttempts; }
    public void setFetchAttempts(int v) { fetchAttempts = v; }
    public String getFetchProto() { return fetchProto; }
    public void setFetchProto(String v) { fetchProto = v == null ? "" : v; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String v) { errorDetail = v == null ? "" : v; }
    public String getIndexable() { return indexable; }
    public void setIndexable(String v) { indexable = v == null ? "" : v; }
    public String getIssues() { return issues; }
    public void setIssues(String v) { issues = v == null ? "" : v; }
    public String getContentKind() { return contentKind; }
    public void setContentKind(String v) { contentKind = v; }

    public List<LinkRef> getOutlinkRefs() { return outlinkRefs; }
    public List<ImageRef> getImageRefs() { return imageRefs; }
    public List<String> getIssueList() { return issueList; }
    public void addIssue(String i) { issueList.add(i); }
    public void rebuildIssueString() { issues = String.join("; ", issueList); }
    public String getHtmlSnippet() { return htmlSnippet; }
    public void setHtmlSnippet(String v) { htmlSnippet = v == null ? "" : v; }
}
