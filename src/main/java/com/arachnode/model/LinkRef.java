package com.arachnode.model;

/** One hyperlink instance: target URL + anchor text + rel. Powers Outlinks/Inlinks tabs. */
public class LinkRef {
    private String target = "";
    private String anchor = "";
    private String rel = "";

    public LinkRef() {}
    public LinkRef(String target, String anchor, String rel) {
        this.target = target == null ? "" : target;
        this.anchor = anchor == null ? "" : anchor;
        this.rel = rel == null ? "" : rel;
    }

    public String getTarget() { return target; }
    public void setTarget(String v) { target = v == null ? "" : v; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String v) { anchor = v == null ? "" : v; }
    public String getRel() { return rel; }
    public void setRel(String v) { rel = v == null ? "" : v; }
}
