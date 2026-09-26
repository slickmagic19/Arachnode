package com.arachnode.crawler;

import com.arachnode.model.CrawlConfig;
import com.arachnode.model.CrawledPage;
import com.arachnode.model.LinkRef;
import com.arachnode.util.UrlUtil;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Breadth-first, high-throughput crawler.
 * Efficiency: single pooled HTTP/2 client, virtual threads (1 per URL, cheap
 * blocking I/O), lock-free dedup via ConcurrentHashMap, manual redirect
 * tracking so chains/loops are reported like Screaming Frog.
 */
public class SeoCrawler {
    public interface Listener {
        void onPage(CrawledPage page);
        void onProgress(int crawled, int queued, int active);
        void onFinished(int crawled);
        void onMessage(String msg);
    }

    private final CrawlConfig config;
    private final Listener listener;
    private final HttpClient http;
    private final RobotsCache robots;
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> depthOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> inlinkCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<LinkRef>> inlinkFrom = new ConcurrentHashMap<>();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger active = new AtomicInteger(0);
    private final AtomicInteger crawled = new AtomicInteger(0);
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private ExecutorService pool;
    private String startHost;
    private String initialUrl;
    private final AtomicBoolean schemeFallbackDone = new AtomicBoolean(false);
    private Thread dispatcher;

    public SeoCrawler(CrawlConfig config, Listener listener) {
        this.config = config.copy();
        this.listener = listener;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER) // track chains manually
                .version(HttpClient.Version.HTTP_2)
                .executor(Executors.newCachedThreadPool())
                .build();
        this.robots = new RobotsCache(http, config.userAgent);
    }

    public void start() {
        stop.set(false); paused.set(false);
        seen.clear(); depthOf.clear(); inlinkCount.clear(); inlinkFrom.clear();
        queue.clear();
        crawled.set(0); active.set(0);
        String start = UrlUtil.normalize(config.startUrl, null);
        if (start == null) { listener.onMessage("Invalid start URL: " + config.startUrl); listener.onFinished(0); return; }
        startHost = UrlUtil.hostOf(start);
        initialUrl = start;
        schemeFallbackDone.set(false);
        seen.put(start, true); depthOf.put(start, 0); queue.add(start);

        pool = Executors.newVirtualThreadPerTaskExecutor(); // Java 21: thousands of cheap threads
        // Fallback for older JDKs: uncomment next line and comment the one above
        // pool = Executors.newFixedThreadPool(Math.max(1, config.threads));

        dispatcher = Thread.ofPlatform().daemon().name("arachnode-dispatcher").start(() -> {
            try {
                while (!stop.get()) {
                    if (paused.get()) { sleep(200); continue; }
                    if (crawled.get() >= config.maxUrls) break;
                    if (active.get() >= Math.max(1, config.threads)) { sleep(5); continue; }
                    String next = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        if (active.get() == 0) break; // drained
                        continue;
                    }
                    active.incrementAndGet();
                    pool.submit(() -> { try { fetchOne(next); } finally { active.decrementAndGet(); } });
                    listener.onProgress(crawled.get(), queue.size(), active.get());
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            shutdown();
            listener.onFinished(crawled.get());
        });
    }

    public void pause(boolean p) { paused.set(p); listener.onMessage(p ? "Paused." : "Resumed."); }
    public boolean isPaused() { return paused.get(); }
    public void stop() { stop.set(true); listener.onMessage("Stopping…"); }

    private void shutdown() {
        if (pool != null) { pool.shutdown(); try { pool.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private void fetchOne(String url) {
        if (stop.get()) return;
        if (crawled.get() >= config.maxUrls) return;
        int depth = depthOf.getOrDefault(url, 0);

        try {
            if (config.respectRobots && !robots.isAllowed(url)) {
                CrawledPage blocked = new CrawledPage(url, depth);
                blocked.setStatusCode(0); blocked.setStatusText("Blocked by robots.txt");
                blocked.addIssue("Blocked by robots.txt"); blocked.rebuildIssueString();
                emit(blocked);
                return;
            }

            HttpResponse<byte[]> res = null;
            Exception fetchError = null;
            long totalMs = 0;
            long delayBeforeNext = 0;
            // Two attempts: transient network faults, 429 throttling and flaky
            // 5xx (e.g. WP fatals under concurrency) are retried once.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (stop.get()) break;
                if (delayBeforeNext > 0) sleep(delayBeforeNext);
                delayBeforeNext = 0;
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(config.timeoutSeconds))
                        .header("User-Agent", config.userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/*,*/*;q=0.8")
                        .header("Accept-Encoding", "gzip")
                        .GET().build();
                long t0 = System.nanoTime();
                try {
                    res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    fetchError = ie; res = null; break;
                } catch (Exception e) {
                    fetchError = e; res = null; delayBeforeNext = 700;
                    continue;
                }
                totalMs += (System.nanoTime() - t0) / 1_000_000;
                fetchError = null;
                int sc = res.statusCode();
                if (attempt == 0 && (sc == 429 || sc == 500 || sc == 502 || sc == 503 || sc == 504)) {
                    delayBeforeNext = sc == 429 ? retryAfterMs(res) : 1500;
                    continue;
                }
                break;
            }

            if (res == null) {
                // Start URL dead on HTTPS (no TLS / refused): try plain HTTP once.
                if (url.equals(initialUrl) && initialUrl != null && initialUrl.startsWith("https://")
                        && schemeFallbackDone.compareAndSet(false, true)) {
                    String httpUrl = "http://" + initialUrl.substring("https://".length());
                    listener.onMessage("HTTPS failed (" + (fetchError == null ? "no response" : shortMsg(fetchError)) + "), retrying with HTTP…");
                    seen.put(httpUrl, true); depthOf.put(httpUrl, 0); queue.add(httpUrl);
                    return;
                }
                CrawledPage nr = new CrawledPage(url, depth);
                nr.setStatusText(fetchError == null ? "No response" : "Fetch error: " + shortMsg(fetchError));
                nr.addIssue(fetchError == null ? "No Response" : "Fetch Error: " + shortMsg(fetchError));
                nr.rebuildIssueString();
                emit(nr);
                return;
            }

            int code = res.statusCode();
            CrawledPage page = new CrawledPage(url, depth);
            page.setResponseTimeMs(totalMs);
            page.setStatusCode(code);
            page.setStatusText(reason(code));
            String ctype = res.headers().firstValue("content-type").orElse("");
            page.setContentType(ctype);
            page.setXRobots(res.headers().firstValue("x-robots-tag").orElse("").toLowerCase());
            byte[] body = res.body() == null ? new byte[0] : res.body();
            // Java's HttpClient doesn't auto-decompress: gunzip saves most of the transfer.
            String enc = res.headers().firstValue("content-encoding").orElse("");
            if (body.length > 0 && enc.contains("gzip")) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gis.readAllBytes();
                } catch (Exception ignored) { /* keep raw bytes */ }
            }
            page.setSizeBytes(body.length);
            page.setInlinks(inlinkCount.getOrDefault(url, new AtomicInteger(0)).get());

            // SF-style: each redirect hop is its own row; the target is crawled
            // separately. (Never auto-follow: that hid targets and turned
            // /page -> /page/ slash redirects into false self-loops.)
            if (code >= 300 && code < 400) {
                page.setContentKind("Redirect");
                String loc = res.headers().firstValue("location").orElse("");
                String target = loc.isEmpty() ? null : UrlUtil.normalize(loc, url);
                if (target == null) {
                    page.addIssue("Redirect Without Location");
                } else {
                    page.setRedirectChain(code + " → " + target);
                    page.addIssue("Redirect " + code + " → " + target);
                    enqueueTarget(target, url, depth);
                }
                page.rebuildIssueString();
                emit(page);
                return;
            }

            boolean isHtml = ctype.contains("html") || ctype.isEmpty();
            if (isHtml && body.length > 0) {
                String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                page.setContentKind("HTML");
                Document doc = SeoAnalyzer.parse(html, url); // parsed once, shared below
                SeoAnalyzer.analyze(page, html, url, doc);
                // discover links (BFS) — from every HTML page, like SF
                List<LinkRef> links = SeoAnalyzer.extractLinks(doc);
                page.setOutlinks(links.size());
                page.getOutlinkRefs().addAll(links.stream().limit(500).toList());
                if (depth < config.maxDepth) {
                    for (LinkRef link : links) {
                        if (stop.get() || crawled.get() + queue.size() >= config.maxUrls) break;
                        String n = UrlUtil.normalize(link.getTarget(), url);
                        if (n == null) continue;
                        if (!config.followExternal && !UrlUtil.inScope(n, startHost, config.includeSubdomains)) {
                            // record external as stub page (like SF External tab) once
                            if (seen.putIfAbsent("ext:" + n, true) == null) {
                                CrawledPage ext = new CrawledPage(n, depth + 1);
                                ext.setContentKind("External"); ext.setStatusText("Not crawled (external)");
                                ext.addIssue("External Link"); ext.rebuildIssueString();
                                emitExternal(ext);
                            }
                            continue;
                        }
                        noteLink(n, url, link.getAnchor(), link.getRel());
                        if (seen.putIfAbsent(n, true) == null) {
                            depthOf.put(n, depth + 1);
                            queue.add(n);
                        }
                    }
                }
                // images already counted in analyzer
            } else {
                // non-HTML: images, css, js, pdf…
                if (ctype.contains("image")) page.setContentKind("Image");
                else if (ctype.contains("css")) page.setContentKind("CSS");
                else if (ctype.contains("javascript")) page.setContentKind("JS");
                else if (code >= 400) page.setContentKind("Error");
                else page.setContentKind("Other");
                if (code >= 400 && code < 500) page.addIssue("Client Error " + code);
                if (code >= 500) page.addIssue("Server Error " + code);
                page.rebuildIssueString();
            }
            emit(page);
        } catch (Exception e) {
            // Only processing-stage failures reach here (fetch has its own retries above).
            CrawledPage err = new CrawledPage(url, depth);
            err.setStatusText("Processing error: " + shortMsg(e));
            err.addIssue("Processing Error: " + shortMsg(e)); err.rebuildIssueString();
            emit(err);
        }
    }

    /** Queue a redirect target (or record an external stub). Self-redirects
     * can't loop: an already-seen target is simply not re-queued. */
    private void enqueueTarget(String target, String source, int depth) {
        if (!config.followExternal && !UrlUtil.inScope(target, startHost, config.includeSubdomains)) {
            if (seen.putIfAbsent("ext:" + target, true) == null) {
                CrawledPage ext = new CrawledPage(target, depth + 1);
                ext.setContentKind("External"); ext.setStatusText("Not crawled (external)");
                ext.addIssue("External Link"); ext.rebuildIssueString();
                emitExternal(ext);
            }
            return;
        }
        noteLink(target, source, "", "");
        if (depth + 1 <= config.maxDepth && seen.putIfAbsent(target, true) == null) {
            depthOf.put(target, depth + 1);
            queue.add(target);
        }
    }

    private void noteLink(String target, String source, String anchor, String rel) {
        inlinkCount.computeIfAbsent(target, k -> new AtomicInteger(0)).incrementAndGet();
        inlinkFrom.computeIfAbsent(target, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                .add(new LinkRef(source, anchor, rel));
    }

    /** Live index: target URL -> link instances pointing at it. Powers the Inlinks tab. */
    public Map<String, List<LinkRef>> getInlinkIndex() { return inlinkFrom; }

    private void emit(CrawledPage p) {
        crawled.incrementAndGet();
        try { listener.onPage(p); } catch (Exception ignored) {}
        listener.onProgress(crawled.get(), queue.size(), active.get());
    }

    private void emitExternal(CrawledPage p) {
        try { listener.onPage(p); } catch (Exception ignored) {}
    }

    /** Honour Retry-After (seconds) on 429, clamped to 1–10s. */
    private static long retryAfterMs(HttpResponse<?> res) {
        try {
            String v = res.headers().firstValue("retry-after").orElse("").trim();
            if (!v.isEmpty()) return Math.min(Math.max(Long.parseLong(v), 1), 10) * 1000;
        } catch (Exception ignored) {}
        return 3000;
    }

    /** Short human-readable cause for error rows ("request timed out", …). */
    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        m = m.trim();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    private static String reason(int code) {
        return switch (code) {
            case 200 -> "OK"; case 201 -> "Created"; case 204 -> "No Content";
            case 301 -> "Moved Permanently"; case 302 -> "Found"; case 303 -> "See Other";
            case 304 -> "Not Modified"; case 307 -> "Temporary Redirect"; case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request"; case 401 -> "Unauthorized"; case 403 -> "Forbidden";
            case 404 -> "Not Found"; case 410 -> "Gone"; case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error"; case 502 -> "Bad Gateway"; case 503 -> "Service Unavailable";
            default -> code >= 200 && code < 300 ? "Success" : code >= 300 && code < 400 ? "Redirect" : "Error";
        };
    }
}
