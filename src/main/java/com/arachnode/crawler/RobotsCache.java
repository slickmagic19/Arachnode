package com.arachnode.crawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal robots.txt cache: fetches once per host, honours Disallow (+ wildcard *). */
public class RobotsCache {
    private final HttpClient http;
    private final String userAgent;
    private final Map<String, List<String>> disallows = new ConcurrentHashMap<>();
    private final Map<String, Boolean> fetched = new ConcurrentHashMap<>();

    public RobotsCache(HttpClient http, String userAgent) {
        this.http = http; this.userAgent = userAgent;
    }

    public boolean isAllowed(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost().toLowerCase();
            String key = u.getScheme() + "://" + host;
            if (fetched.putIfAbsent(key, true) == null) fetch(key);
            String path = (u.getRawPath() == null ? "/" : u.getRawPath())
                    + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
            for (String d : disallows.getOrDefault(key, List.of())) {
                if (d.isEmpty()) continue;
                if (d.equals("/") || path.startsWith(d)) return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void fetch(String origin) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", userAgent).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            List<String> rules = new ArrayList<>();
            if (res.statusCode() == 200 && res.body() != null) {
                boolean applies = false;
                for (String line : res.body().split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    int c = t.indexOf(':');
                    if (c < 0) continue;
                    String k = t.substring(0, c).trim().toLowerCase();
                    String v = t.substring(c + 1).trim().split("\\s+")[0];
                    if (k.equals("user-agent")) applies = v.equals("*") || userAgent.toLowerCase().contains(v.toLowerCase());
                    else if (k.equals("disallow") && applies) rules.add(v);
                }
            }
            disallows.put(origin, rules);
        } catch (Exception e) {
            disallows.put(origin, List.of());
        }
    }
}
