package com.arachnode.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** GitHub Releases update check: latest tag, notes, portable-asset download. */
public final class UpdateChecker {
    private UpdateChecker() {}

    public record ReleaseInfo(String tag, String name, String notes, String pageUrl,
                              String assetName, String assetUrl) {}

    private static final HttpClient API = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final HttpClient DOWNLOAD = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static String apiUrl() {
        return "https://api.github.com/repos/" + AppVersion.repo() + "/releases/latest";
    }

    public static String releasesPage() {
        return "https://github.com/" + AppVersion.repo() + "/releases";
    }

    /** Latest release, or null when offline / no releases yet. Never throws. */
    public static ReleaseInfo latest() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Arachnode/" + AppVersion.current())
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            HttpResponse<String> res = API.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) return null; // no releases published yet
            if (res.statusCode() != 200 || res.body() == null) return null;
            JSONObject o = new JSONObject(res.body());
            String tag = o.optString("tag_name", "").trim();
            if (tag.isEmpty()) return null;
            String assetName = "", assetUrl = "";
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String n = a.optString("name", "");
                    String u = a.optString("browser_download_url", "");
                    if (u.isEmpty()) continue;
                    if (assetUrl.isEmpty()) { assetName = n; assetUrl = u; } // first fallback
                    if (n.toLowerCase().endsWith(".exe")) { assetName = n; assetUrl = u; break; }
                }
            }
            return new ReleaseInfo(tag, o.optString("name", tag),
                    o.optString("body", ""), o.optString("html_url", releasesPage()),
                    assetName, assetUrl);
        } catch (Exception e) {
            return null; // offline, rate-limited, DNS… — caller reports politely
        }
    }

    public static boolean isNewer(String tag) {
        if (tag == null || tag.isBlank()) return false;
        return AppVersion.compare(tag, AppVersion.current()) > 0;
    }

    /** Download the portable asset to ~/Downloads. Returns the file, or null on failure. */
    public static Path download(ReleaseInfo rel) {
        if (rel == null || rel.assetUrl() == null || rel.assetUrl().isEmpty()) return null;
        try {
            String fileName = (rel.assetName() == null || rel.assetName().isEmpty())
                    ? "Arachnode-Portable.exe" : rel.assetName();
            Path dest = Path.of(System.getProperty("user.home"), "Downloads", fileName);
            Files.createDirectories(dest.getParent());
            Path tmp = Files.createTempFile("arachnode-update", ".exe");
            HttpRequest req = HttpRequest.newBuilder(URI.create(rel.assetUrl()))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Arachnode/" + AppVersion.current())
                    .GET().build();
            HttpResponse<Path> res = DOWNLOAD.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                Files.deleteIfExists(tmp);
                return null;
            }
            long size = Files.size(tmp);
            if (size < 1_000_000) { // not a real build (error page?)
                Files.deleteIfExists(tmp);
                return null;
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }
}
