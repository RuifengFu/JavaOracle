package edu.tju.ista.llm4test.llm.tools;

import java.time.Instant;

public class SearchResult {
    private String title;
    private String snippet;
    private String url;
    private Instant timestamp;

    // Constructor with timestamp
    public SearchResult(String title, String snippet, String url, Instant timestamp) {
        this.title = title;
        this.snippet = snippet;
        this.url = url;
        this.timestamp = timestamp;
    }

    // Constructor without timestamp
    public SearchResult(String title, String snippet, String url) {
        this(title, snippet, url, null);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "SearchResult{\n" +
                "title='" + title + '\'' +
                ",\nsnippet='" + snippet + '\'' +
                ",\nurl='" + url + '\'' +
                ",\ntimestamp=" + timestamp +
                "\n}";
    }
}