package com.arachnode.util;

import java.net.URI;

/** URL normalisation + scope checks. Critical for crawl efficiency (dedup). */
public final class UrlUtil {
    private UrlUtil() {}

    public static String normalize(String raw, String base) {
        try {
            // Tolerate user input like "example.com/page" (no scheme) for start URLs.
            if (base == null && raw != null && !raw.contains("://")) raw = "https://" + raw;
            URI u = base == null ? new URI(raw) : new URI(base).resolve(raw);
            String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            if (host.isEmpty()) return null;
            int port = u.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            String query = u.getRawQuery();
            String s = scheme + "://" + host + (port == -1 ? "" : ":" + port) + path + (query != null ? "?" + query : "");
            // Keep trailing slashes: /page and /page/ are distinct URLs (servers
            // commonly 301 one to the other; stripping the slash caused false loops).
            // Fragments are never fetched (dropped by resolving without fragment).
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static String hostOf(String url) {
        try { return new URI(url).getHost().toLowerCase(); }
        catch (Exception e) { return ""; }
    }

    public static boolean inScope(String candidate, String startHost, boolean includeSubdomains) {
        String h = hostOf(candidate);
        if (h.isEmpty()) return false;
        if (h.equals(startHost)) return true;
        return includeSubdomains && h.endsWith("." + startHost);
    }
}
