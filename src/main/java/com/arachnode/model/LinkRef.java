package com.arachnode.model;

/** One hyperlink instance: source page -> target URL + anchor + rel.
 *  Outlinks tab shows target; Inlinks tab shows source. */
public class LinkRef {
    private String target = "";
    private String source = "";
    private String anchor = "";
    private String rel = "";

    public LinkRef() {}
    public LinkRef(String target, String anchor, String rel) {
        this("", target, anchor, rel);
    }
    public LinkRef(String source, String target, String anchor, String rel) {
        this.source = source == null ? "" : source;
        this.target = target == null ? "" : target;
        this.anchor = anchor == null ? "" : anchor;
        this.rel = rel == null ? "" : rel;
    }

    public String getTarget() { return target; }
    public void setTarget(String v) { target = v == null ? "" : v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String v) { anchor = v == null ? "" : v; }
    public String getRel() { return rel; }
    public void setRel(String v) { rel = v == null ? "" : v; }
}
