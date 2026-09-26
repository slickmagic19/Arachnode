# Arachnode — SEO Spider (Screaming Frog clone)

Java 21 + JavaFX desktop crawler with Screaming Frog-style UI and audit logic.

## Features (v1 parity)
- URL bar + Start / Pause / Stop, threads + max-URLs config (default 500 like SF free tier)
- Breadth-first crawl, virtual threads, pooled HTTP/2 client, robots.txt respect
- Tabs: Internal, External, Response Codes, Page Titles, Meta Description, H1, H2, Images, Directives, Canonicals, Issues
- Per-URL audit: titles, meta, H1/H2, word count, canonical, robots directives, images/alt, redirect chains + loops, thin content, long URLs
- Right-side Issues Overview with live counts, bottom URL Details pane
- Search filter, copy URLs, double-click to open in browser, status-code row colouring
- Export CSV + Generate XML Sitemap (File menu)

## Efficiency notes
- Single shared `HttpClient` (HTTP/2, connection pooling) — no per-request client creation
- `Executors.newVirtualThreadPerTaskExecutor()` — thousands of cheap blocking-IO threads
- Lock-free dedup (`ConcurrentHashMap` + `LinkedBlockingQueue`), manual redirect tracking (chains/loops)
- JSoup parses HTML only; non-HTML (images/CSS/JS) recorded without parsing

## Prerequisites
- JDK 21+ and Maven 3.9+ on PATH (`java -version`, `mvn -version`)

Install on Windows (winget):
```powershell
winget install Microsoft.OpenJDK.21
winget install Apache.Maven
```

## Run
```powershell
cd "C:\Users\Superior\Desktop\Projects\Arachnode"
mvn javafx:run
```

Package:
```powershell
mvn package
java -jar target\arachnode-1.0.0.jar
```

## Next steps (roadmap to full SF parity)
- JS rendering via headless Chromium, custom extraction (XPath/CSS/regex), custom search
- Duplicate detection (MD5 + near-duplicate), hreflang audit, structured-data validation
- Save/open crawls, scheduling, crawl comparison, visualisations
