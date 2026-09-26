# Arachnode — SEO Spider

Java 21 + JavaFX desktop website crawler with a full SEO audit UI.

## Features
- URL bar + Start / Pause / Stop, threads + max-URLs config, spider configuration dialog
- Breadth-first crawl, virtual threads, pooled HTTP/2 client, robots.txt respect
- Top tabs: All, Internal, External, Security, Response Codes, URL, Page Titles,
  Meta Description, Meta Keywords, H1, H2, Content, Images, Canonicals, Pagination,
  Directives, Hreflang, JavaScript, Links, AMP, Structured Data, Custom Search,
  Duplicates, Issues — with a Configure-Tabs menu
- Per-URL audit: titles, meta, H1/H2, word count, canonical, robots directives,
  images/alt, redirect chains, thin content, long URLs, pagination, hreflang,
  scripts, JSON-LD, cookies, HTTP headers
- Clickable Issues Overview (internal URLs), clickable OK/redirect/error/external stats
- Quick search + Advanced Table Search (column/operator, AND/OR groups) + Custom Search
- Bottom detail tabs: URL Details, Inlinks, Outlinks, Image Details, Resources,
  SERP Snippet, View Source, HTTP Headers, Cookies, Structured Data Details,
  Duplicate Details
- Crawl rate (average + current URL/s), in-bar progress %, CSV export + XML sitemap

## Efficiency notes
- Single shared `HttpClient` (HTTP/2, connection pooling) — no per-request client creation
- `Executors.newVirtualThreadPerTaskExecutor()` — thousands of cheap blocking-IO threads
- Lock-free dedup (`ConcurrentHashMap` + `LinkedBlockingQueue`), manual redirect tracking
- JSoup parses HTML only; non-HTML (images/CSS/JS) recorded without parsing
- 5xx/429 retried with backoff + confirm-before-record, so transient errors don't stick

## Run (portable, no install)
Unzip the release and double-click `Arachnode.exe` — Java is bundled, nothing to install.

## Run (developers)
Prerequisites: JDK 21+ and Maven 3.9+ on PATH (`java -version`, `mvn -version`)

```powershell
cd "C:\Users\Achi\Desktop\Arachnode"
mvn javafx:run
```

Build the portable app (needs JDK `jpackage`):
```powershell
mvn package
.\build-portable.ps1
# -> target\dist\Arachnode\Arachnode.exe
```
