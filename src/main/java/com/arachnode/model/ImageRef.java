package com.arachnode.model;

/** One image instance on a page: source + alt text. Powers the Image Details tab. */
public class ImageRef {
    private String src = "";
    private String alt = "";

    public ImageRef() {}
    public ImageRef(String src, String alt) {
        this.src = src == null ? "" : src;
        this.alt = alt == null ? "" : alt;
    }

    public String getSrc() { return src; }
    public void setSrc(String v) { src = v == null ? "" : v; }
    public String getAlt() { return alt; }
    public void setAlt(String v) { alt = v == null ? "" : v; }
    public String getAltStatus() { return alt.isEmpty() ? "Missing" : "OK"; }
}
