package com.arachnode.model;

/** Crawl tuning. Mirrors Screaming Frog spider configuration. */
public class CrawlConfig {
    public String startUrl = "https://example.com";
    public int maxUrls = Integer.MAX_VALUE; // unlimited (paid-SF behaviour)
    public int maxDepth = 12;
    public int threads = 10;
    // Browser-like UA: many servers/WAFs block unknown bot tokens with 403.
    public String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Arachnode/1.0";
    public boolean followExternal = false;
    public boolean includeSubdomains = true; // SF parity: e.g. www. sites won't show all-redirects
    public boolean respectRobots = true;
    public int timeoutSeconds = 20;
    public int maxRedirects = 5;

    public CrawlConfig copy() {
        CrawlConfig c = new CrawlConfig();
        c.startUrl = startUrl; c.maxUrls = maxUrls; c.maxDepth = maxDepth;
        c.threads = threads; c.userAgent = userAgent;
        c.followExternal = followExternal; c.includeSubdomains = includeSubdomains;
        c.respectRobots = respectRobots; c.timeoutSeconds = timeoutSeconds;
        c.maxRedirects = maxRedirects;
        return c;
    }
}
