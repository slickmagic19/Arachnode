package com.arachnode.update;

/** Single source of truth for the running app version (Maven-filtered at build). */
public final class AppVersion {
    private AppVersion() {}

    public static String current() {
        try (var in = AppVersion.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                var p = new java.util.Properties();
                p.load(in);
                String v = p.getProperty("app.version", "").trim();
                if (!v.isEmpty() && !v.startsWith("@")) return v;
            }
        } catch (Exception ignored) {}
        return "1.2.0";
    }

    public static String repo() {
        try (var in = AppVersion.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                var p = new java.util.Properties();
                p.load(in);
                String r = p.getProperty("app.repo", "").trim();
                if (!r.isEmpty() && !r.startsWith("@")) return r;
            }
        } catch (Exception ignored) {}
        return "slickmagic19/Arachnode";
    }

    /** Compare dotted versions after stripping a leading v ("v1.2.0" == "1.2.0"). */
    public static int compare(String a, String b) {
        String[] pa = norm(a).split("[.\\-]");
        String[] pb = norm(b).split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            try {
                int d = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
                if (d != 0) return d;
            } catch (NumberFormatException e) {
                // Release beats prerelease ("1.2.0" > "1.2.0-SNAPSHOT"); else lexical.
                boolean qa = !sa.matches("\\d+");
                boolean qb = !sb.matches("\\d+");
                if (qa != qb) return qa ? -1 : 1;
                int d = sa.compareTo(sb);
                if (d != 0) return d;
            }
        }
        return 0;
    }

    private static String norm(String v) {
        v = v == null ? "" : v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v.isEmpty() ? "0" : v;
    }
}
