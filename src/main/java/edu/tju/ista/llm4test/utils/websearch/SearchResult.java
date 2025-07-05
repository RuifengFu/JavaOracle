package edu.tju.ista.llm4test.utils.websearch;

import edu.tju.ista.llm4test.utils.websearch.api.WebPageValue;

/**
 * 封装网页搜索结果的数据模型。
 */
public class SearchResult {
    private final String id;
    private final String name;
    private final String url;
    private final String displayUrl;
    private final String snippet;
    private final String summary;
    private final String siteName;
    private final String siteIcon;
    private final String datePublished;
    private final String dateLastCrawled;
    private String content;
    private int rank;
    
    /**
     * rerankScore，分数的范围从0到1。分数越高，表示文档与查询的语义相关性越强，越符合用户需求。
     * <ul>
     *   <li>0.75 ~ 1: 该文档高度相关并完全回答了问题，尽管可能包含与问题无关的额外文本。</li>
     *   <li>0.5 ~ 0.75: 该文档与问题是相关的，但缺乏使其完整的细节。</li>
     *   <li>0.2 ~ 0.5: 该文档与问题有一定的相关性；它部分回答了问题，或者只解决了问题的某些方面。</li>
     *   <li>0.1 ~ 0.2: 该文档与问题相关，但仅回答了一小部分。</li>
     *   <li>0 ~ 0.1: 该文档与问题无关紧要。</li>
     * </ul>
     */
    private double relevanceScore = -1.0;

    public SearchResult(String content) {
        this(null, 0);
        this.content = content;
    }


    public SearchResult(WebPageValue webPage, int rank) {
        this.id = webPage.id;
        this.name = webPage.name;
        this.url = webPage.url;
        this.displayUrl = webPage.displayUrl;
        this.snippet = webPage.snippet;
        this.summary = webPage.summary;
        this.siteName = webPage.siteName;
        this.siteIcon = webPage.siteIcon;
        this.datePublished = webPage.datePublished;
        this.dateLastCrawled = webPage.dateLastCrawled;
        this.rank = rank;
    }
    
    // 兼容旧版本的构造函数
    public SearchResult(String title, String snippet, String url, int rank) {
        this.id = null;
        this.name = title;
        this.url = url;
        this.displayUrl = url;
        this.snippet = snippet;
        this.summary = null;
        this.siteName = null;
        this.siteIcon = null;
        this.datePublished = null;
        this.dateLastCrawled = null;
        this.rank = rank;
    }
    
    // Copy constructor
    private SearchResult(SearchResult original) {
        this.id = original.id;
        this.name = original.name;
        this.url = original.url;
        this.displayUrl = original.displayUrl;
        this.snippet = original.snippet;
        this.summary = original.summary;
        this.siteName = original.siteName;
        this.siteIcon = original.siteIcon;
        this.datePublished = original.datePublished;
        this.dateLastCrawled = original.dateLastCrawled;
        this.rank = original.rank;
        this.relevanceScore = original.relevanceScore;
    }
    
    // Getters
    public String getId() { return id; }
    public String getTitle() { return name; } // 兼容性方法
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getDisplayUrl() { return displayUrl; }
    public String getSnippet() { return snippet; }
    public String getSummary() { return summary; }
    public String getSiteName() { return siteName; }
    public String getSiteIcon() { return siteIcon; }
    public String getDatePublished() { return datePublished; }
    public String getDateLastCrawled() { return dateLastCrawled; }
    public int getRank() { return rank; }
    public double getRelevanceScore() { return relevanceScore; }
    
    public void setRelevanceScore(double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }
    
    @Override
    public String toString() {
        String base = String.format("SearchResult{rank=%d, name='%s', url='%s', snippet='%s'}",
                           rank, name, url, snippet);
        if (relevanceScore >= 0) {
            return base.substring(0, base.length() - 1) + String.format(", relevanceScore=%.4f}", relevanceScore);
        }
        return base;
    }
} 