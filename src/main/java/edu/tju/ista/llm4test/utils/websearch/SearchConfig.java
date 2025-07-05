package edu.tju.ista.llm4test.utils.websearch;

/**
 * 配置WebSearch操作的参数。
 */
public class SearchConfig {
    private String apiKey;
    private Freshness freshness = Freshness.NO_LIMIT;
    private boolean summary = false;
    private String include;
    private String exclude;
    private int count = 10;
    private int timeout = 30; // 30秒超时
    private boolean enableLogging = true;
    
    public SearchConfig setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }
    
    public SearchConfig setFreshness(Freshness freshness) {
        this.freshness = freshness;
        return this;
    }
    
    public SearchConfig setFreshness(String customFreshness) {
        // 支持自定义时间范围，如 "2025-01-01..2025-04-06"
        return this;
    }
    
    public SearchConfig setSummary(boolean summary) {
        this.summary = summary;
        return this;
    }
    
    public SearchConfig setInclude(String include) {
        this.include = include;
        return this;
    }
    
    public SearchConfig setExclude(String exclude) {
        this.exclude = exclude;
        return this;
    }
    
    public SearchConfig setCount(int count) {
        this.count = Math.min(Math.max(count, 1), 50); // 限制在1-50之间
        return this;
    }
    
    public SearchConfig setTimeout(int timeout) {
        this.timeout = timeout;
        return this;
    }
    
    public SearchConfig setEnableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
        return this;
    }
    
    // Getters
    public String getApiKey() { return apiKey; }
    public Freshness getFreshness() { return freshness; }
    public boolean isSummary() { return summary; }
    public String getInclude() { return include; }
    public String getExclude() { return exclude; }
    public int getCount() { return count; }
    public int getTimeout() { return timeout; }
    public boolean isEnableLogging() { return enableLogging; }
} 