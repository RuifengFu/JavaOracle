package edu.tju.ista.llm4test.utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;

/**
 * Web搜索工具类 - 基于博查AI Web Search API
 * 支持网页搜索、图片搜索等功能
 */
public class WebSearch {
    
    private static final String API_BASE_URL = "https://api.bochaai.com/v1/web-search";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    
    // 时间范围枚举
    public enum Freshness {
        NO_LIMIT("noLimit"),
        ONE_DAY("oneDay"),
        ONE_WEEK("oneWeek"),
        ONE_MONTH("oneMonth"),
        ONE_YEAR("oneYear");
        
        private final String value;
        
        Freshness(String value) {
            this.value = value;
        }
        
        public String getValue() { return value; }
    }
    
    // 搜索结果类
    public static class SearchResult {
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
        private final int rank;
        
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
        
        @Override
        public String toString() {
            return String.format("SearchResult{rank=%d, name='%s', url='%s', snippet='%s'}", 
                               rank, name, url, snippet);
        }
    }
    
    // 图片搜索结果类
    public static class ImageResult {
        private final String name;
        private final String contentUrl;
        private final String thumbnailUrl;
        private final String hostPageUrl;
        private final String hostPageDisplayUrl;
        private final int width;
        private final int height;
        private final String datePublished;
        
        public ImageResult(ImageValue imageValue) {
            this.name = imageValue.name;
            this.contentUrl = imageValue.contentUrl;
            this.thumbnailUrl = imageValue.thumbnailUrl;
            this.hostPageUrl = imageValue.hostPageUrl;
            this.hostPageDisplayUrl = imageValue.hostPageDisplayUrl;
            this.width = imageValue.width;
            this.height = imageValue.height;
            this.datePublished = imageValue.datePublished;
        }
        
        // Getters
        public String getName() { return name; }
        public String getContentUrl() { return contentUrl; }
        public String getThumbnailUrl() { return thumbnailUrl; }
        public String getHostPageUrl() { return hostPageUrl; }
        public String getHostPageDisplayUrl() { return hostPageDisplayUrl; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getDatePublished() { return datePublished; }
        
        @Override
        public String toString() {
            return String.format("ImageResult{name='%s', contentUrl='%s', size=%dx%d}", 
                               name, contentUrl, width, height);
        }
    }
    
    // 搜索配置类
    public static class SearchConfig {
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
    
    // API请求类
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchRequest {
        @JsonProperty("query")
        public String query;
        
        @JsonProperty("freshness")
        public String freshness;
        
        @JsonProperty("summary")
        public Boolean summary;
        
        @JsonProperty("include")
        public String include;
        
        @JsonProperty("exclude")
        public String exclude;
        
        @JsonProperty("count")
        public Integer count;
    }
    
    // API响应类
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiResponse {
        @JsonProperty("code")
        public int code;
        
        @JsonProperty("log_id")
        public String logId;
        
        @JsonProperty("msg")
        public String msg;
        
        @JsonProperty("data")
        public SearchData data;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchData {
        @JsonProperty("_type")
        public String type;
        
        @JsonProperty("queryContext")
        public QueryContext queryContext;
        
        @JsonProperty("webPages")
        public WebPages webPages;
        
        @JsonProperty("images")
        public Images images;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryContext {
        @JsonProperty("originalQuery")
        public String originalQuery;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebPages {
        @JsonProperty("totalEstimatedMatches")
        public long totalEstimatedMatches;
        
        @JsonProperty("value")
        public List<WebPageValue> value;
        
        @JsonProperty("someResultsRemoved")
        public boolean someResultsRemoved;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebPageValue {
        @JsonProperty("id")
        public String id;
        
        @JsonProperty("name")
        public String name;
        
        @JsonProperty("url")
        public String url;
        
        @JsonProperty("displayUrl")
        public String displayUrl;
        
        @JsonProperty("snippet")
        public String snippet;
        
        @JsonProperty("summary")
        public String summary;
        
        @JsonProperty("siteName")
        public String siteName;
        
        @JsonProperty("siteIcon")
        public String siteIcon;
        
        @JsonProperty("datePublished")
        public String datePublished;
        
        @JsonProperty("dateLastCrawled")
        public String dateLastCrawled;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Images {
        @JsonProperty("value")
        public List<ImageValue> value;
        
        @JsonProperty("isFamilyFriendly")
        public Boolean isFamilyFriendly;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImageValue {
        @JsonProperty("name")
        public String name;
        
        @JsonProperty("contentUrl")
        public String contentUrl;
        
        @JsonProperty("thumbnailUrl")
        public String thumbnailUrl;
        
        @JsonProperty("hostPageUrl")
        public String hostPageUrl;
        
        @JsonProperty("hostPageDisplayUrl")
        public String hostPageDisplayUrl;
        
        @JsonProperty("width")
        public int width;
        
        @JsonProperty("height")
        public int height;
        
        @JsonProperty("datePublished")
        public String datePublished;
    }
    
    // 搜索结果摘要类
    public static class SearchSummary {
        private final String originalQuery;
        private final long totalEstimatedMatches;
        private final int actualResults;
        private final List<String> topDomains;
        private final String combinedSnippets;
        private final boolean someResultsRemoved;
        
        public SearchSummary(SearchData data, List<SearchResult> results) {
            this.originalQuery = data.queryContext != null ? data.queryContext.originalQuery : "";
            this.totalEstimatedMatches = data.webPages != null ? data.webPages.totalEstimatedMatches : 0;
            this.actualResults = results.size();
            this.topDomains = extractTopDomains(results);
            this.combinedSnippets = combineSnippets(results);
            this.someResultsRemoved = data.webPages != null ? data.webPages.someResultsRemoved : false;
        }
        
        private List<String> extractTopDomains(List<SearchResult> results) {
            Map<String, Integer> domainCount = new HashMap<>();
            
            for (SearchResult result : results) {
                try {
                    String domain = extractDomain(result.getUrl());
                    domainCount.put(domain, domainCount.getOrDefault(domain, 0) + 1);
                } catch (Exception e) {
                    // 忽略URL解析错误
                }
            }
            
            return domainCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
        
        private String extractDomain(String url) {
            if (url != null && url.startsWith("http")) {
                String[] parts = url.split("/");
                if (parts.length >= 3) {
                    return parts[2];
                }
            }
            return url != null ? url.split("/")[0] : "";
        }
        
        private String combineSnippets(List<SearchResult> results) {
            StringBuilder combined = new StringBuilder();
            for (SearchResult result : results) {
                if (result.getSnippet() != null && !result.getSnippet().isEmpty()) {
                    combined.append(result.getSnippet()).append(" ");
                }
            }
            return combined.toString().trim();
        }
        
        // Getters
        public String getOriginalQuery() { return originalQuery; }
        public long getTotalEstimatedMatches() { return totalEstimatedMatches; }
        public int getActualResults() { return actualResults; }
        public List<String> getTopDomains() { return topDomains; }
        public String getCombinedSnippets() { return combinedSnippets; }
        public boolean isSomeResultsRemoved() { return someResultsRemoved; }
        
        @Override
        public String toString() {
            return String.format("SearchSummary{query='%s', totalMatches=%d, actualResults=%d, topDomains=%s}", 
                               originalQuery, totalEstimatedMatches, actualResults, topDomains);
        }
    }
    
    private final SearchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public WebSearch() {
        this.config = new SearchConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public WebSearch(SearchConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeout()))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 执行搜索
     * @param query 搜索关键词
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query) {
        return search(query, config.getCount());
    }
    
    /**
     * 执行搜索
     * @param query 搜索关键词
     * @param maxResults 最大结果数量（1-50）
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        
        if (config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("API Key不能为空，请在SearchConfig中设置API Key");
        }
        
        try {
            if (config.isEnableLogging()) {
                LoggerUtil.logOpenAI(Level.INFO, "开始搜索: " + query);
            }
            
            // 构建请求
            SearchRequest request = new SearchRequest();
            request.query = query.trim();
            request.freshness = config.getFreshness().getValue();
            request.summary = config.isSummary();
            request.include = config.getInclude();
            request.exclude = config.getExclude();
            request.count = Math.min(maxResults, 50);
            
            String requestBody = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                ApiResponse apiResponse = objectMapper.readValue(response.body(), ApiResponse.class);
                
                if (apiResponse.code == 200 && apiResponse.data != null) {
                    List<SearchResult> results = parseSearchResults(apiResponse.data);
                    
                    if (config.isEnableLogging()) {
                        LoggerUtil.logOpenAI(Level.INFO, "搜索完成，获得 " + results.size() + " 个结果");
                    }
                    
                    return results;
                } else {
                    throw new RuntimeException("API返回错误: " + apiResponse.msg);
                }
            } else {
                throw new RuntimeException("HTTP请求失败，状态码: " + response.statusCode() + ", 响应: " + response.body());
            }
            
        } catch (IOException | InterruptedException e) {
            if (config.isEnableLogging()) {
                LoggerUtil.logOpenAI(Level.SEVERE, "搜索失败: " + e.getMessage());
            }
            throw new RuntimeException("搜索请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析搜索结果
     */
    private List<SearchResult> parseSearchResults(SearchData data) {
        List<SearchResult> results = new ArrayList<>();
        
        if (data.webPages != null && data.webPages.value != null) {
            int rank = 1;
            for (WebPageValue webPage : data.webPages.value) {
                results.add(new SearchResult(webPage, rank++));
            }
        }
        
        return results;
    }
    
    /**
     * 搜索图片
     * @param query 搜索关键词
     * @return 图片搜索结果列表
     */
    public List<ImageResult> searchImages(String query) {
        return searchImages(query, config.getCount());
    }
    
    /**
     * 搜索图片
     * @param query 搜索关键词
     * @param maxResults 最大结果数量
     * @return 图片搜索结果列表
     */
    public List<ImageResult> searchImages(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        
        if (config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("API Key不能为空，请在SearchConfig中设置API Key");
        }
        
        try {
            if (config.isEnableLogging()) {
                LoggerUtil.logOpenAI(Level.INFO, "开始图片搜索: " + query);
            }
            
            // 构建请求
            SearchRequest request = new SearchRequest();
            request.query = query.trim();
            request.freshness = config.getFreshness().getValue();
            request.summary = config.isSummary();
            request.include = config.getInclude();
            request.exclude = config.getExclude();
            request.count = Math.min(maxResults, 50);
            
            String requestBody = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                ApiResponse apiResponse = objectMapper.readValue(response.body(), ApiResponse.class);
                
                if (apiResponse.code == 200 && apiResponse.data != null) {
                    List<ImageResult> results = parseImageResults(apiResponse.data);
                    
                    if (config.isEnableLogging()) {
                        LoggerUtil.logOpenAI(Level.INFO, "图片搜索完成，获得 " + results.size() + " 个结果");
                    }
                    
                    return results;
                } else {
                    throw new RuntimeException("API返回错误: " + apiResponse.msg);
                }
            } else {
                throw new RuntimeException("HTTP请求失败，状态码: " + response.statusCode() + ", 响应: " + response.body());
            }
            
        } catch (IOException | InterruptedException e) {
            if (config.isEnableLogging()) {
                LoggerUtil.logOpenAI(Level.SEVERE, "图片搜索失败: " + e.getMessage());
            }
            throw new RuntimeException("图片搜索请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 解析图片搜索结果
     */
    private List<ImageResult> parseImageResults(SearchData data) {
        List<ImageResult> results = new ArrayList<>();
        
        if (data.images != null && data.images.value != null) {
            for (ImageValue imageValue : data.images.value) {
                results.add(new ImageResult(imageValue));
            }
        }
        
        return results;
    }
    
    /**
     * 搜索特定网站
     * @param query 搜索关键词
     * @param site 网站域名
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSite(String query, String site) {
        SearchConfig siteConfig = new SearchConfig()
                .setApiKey(config.getApiKey())
                .setInclude(site)
                .setFreshness(config.getFreshness())
                .setSummary(config.isSummary())
                .setCount(config.getCount())
                .setTimeout(config.getTimeout())
                .setEnableLogging(config.isEnableLogging());
        
        WebSearch siteSearch = new WebSearch(siteConfig);
        return siteSearch.search(query);
    }
    
    /**
     * 排除特定网站的搜索
     * @param query 搜索关键词
     * @param excludeSites 要排除的网站域名
     * @return 搜索结果列表
     */
    public List<SearchResult> searchExcluding(String query, String... excludeSites) {
        String excludeStr = String.join("|", excludeSites);
        
        SearchConfig excludeConfig = new SearchConfig()
                .setApiKey(config.getApiKey())
                .setExclude(excludeStr)
                .setFreshness(config.getFreshness())
                .setSummary(config.isSummary())
                .setCount(config.getCount())
                .setTimeout(config.getTimeout())
                .setEnableLogging(config.isEnableLogging());
        
        WebSearch excludeSearch = new WebSearch(excludeConfig);
        return excludeSearch.search(query);
    }
    
    /**
     * 获取搜索结果摘要
     * @param query 搜索关键词
     * @return 搜索摘要
     */
    public SearchSummary getSearchSummary(String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        
        if (config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("API Key不能为空，请在SearchConfig中设置API Key");
        }
        
        try {
            // 构建请求
            SearchRequest request = new SearchRequest();
            request.query = query.trim();
            request.freshness = config.getFreshness().getValue();
            request.summary = true; // 强制启用摘要
            request.include = config.getInclude();
            request.exclude = config.getExclude();
            request.count = config.getCount();
            
            String requestBody = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                ApiResponse apiResponse = objectMapper.readValue(response.body(), ApiResponse.class);
                
                if (apiResponse.code == 200 && apiResponse.data != null) {
                    List<SearchResult> results = parseSearchResults(apiResponse.data);
                    return new SearchSummary(apiResponse.data, results);
                } else {
                    throw new RuntimeException("API返回错误: " + apiResponse.msg);
                }
            } else {
                throw new RuntimeException("HTTP请求失败，状态码: " + response.statusCode());
            }
            
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("获取搜索摘要失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 静态便捷方法
     */
    public static List<SearchResult> quickSearch(String query, String apiKey) {
        SearchConfig config = new SearchConfig().setApiKey(apiKey);
        return new WebSearch(config).search(query);
    }
    
    public static List<SearchResult> quickSearch(String query, String apiKey, int maxResults) {
        SearchConfig config = new SearchConfig().setApiKey(apiKey).setCount(maxResults);
        return new WebSearch(config).search(query, maxResults);
    }
    
    // 测试方法
    public static void main(String[] args) {
        try {
            System.out.println("=== WebSearch API 测试 ===");
            
            // 注意：需要设置真实的API Key才能进行测试
            String apiKey = System.getenv("BOCHA_API_KEY"); // 从环境变量获取API Key
            
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.out.println("请设置环境变量 BOCHA_API_KEY 或在代码中直接设置API Key进行测试");
                System.out.println("测试跳过，仅演示基本对象创建功能");
                
                // 基本功能测试
                SearchConfig config = new SearchConfig()
                        .setApiKey("test-api-key")
                        .setFreshness(Freshness.NO_LIMIT)
                        .setSummary(true)
                        .setCount(10);
                
                WebSearch webSearch = new WebSearch(config);
                System.out.println("WebSearch对象创建成功");
                System.out.println("配置信息:");
                System.out.println("- 时间范围: " + config.getFreshness().getValue());
                System.out.println("- 启用摘要: " + config.isSummary());
                System.out.println("- 最大结果数: " + config.getCount());
                
                return;
            }
            
            SearchConfig config = new SearchConfig()
                    .setApiKey(apiKey)
                    .setFreshness(Freshness.NO_LIMIT)
                    .setSummary(true)
                    .setCount(5);
            
            WebSearch webSearch = new WebSearch(config);
            
            // 测试1: 基本搜索
            System.out.println("\n1. 基本搜索测试");
            List<SearchResult> results = webSearch.search("JTreg Java测试框架");
            
            System.out.println("搜索关键词: JTreg Java测试框架");
            System.out.println("搜索结果数量: " + results.size());
            
            for (SearchResult result : results) {
                System.out.println("排名: " + result.getRank());
                System.out.println("标题: " + result.getName());
                System.out.println("URL: " + result.getUrl());
                System.out.println("网站: " + result.getSiteName());
                System.out.println("摘要: " + result.getSnippet());
                if (result.getSummary() != null && !result.getSummary().isEmpty()) {
                    System.out.println("详细摘要: " + result.getSummary());
                }
                System.out.println("---");
            }
            
            // 测试2: 网站搜索
            System.out.println("\n2. 网站搜索测试");
            List<SearchResult> siteResults = webSearch.searchSite("JTreg documentation", "openjdk.org");
            System.out.println("在openjdk.org搜索JTreg documentation:");
            for (SearchResult result : siteResults) {
                System.out.println("- " + result.getName());
                System.out.println("  " + result.getUrl());
            }
            
            // 测试3: 图片搜索
            System.out.println("\n3. 图片搜索测试");
            List<ImageResult> imageResults = webSearch.searchImages("JTreg test framework", 3);
            System.out.println("搜索JTreg test framework相关图片:");
            for (ImageResult imageResult : imageResults) {
                System.out.println("- " + imageResult.getName());
                System.out.println("  图片URL: " + imageResult.getContentUrl());
                System.out.println("  尺寸: " + imageResult.getWidth() + "x" + imageResult.getHeight());
            }
            
            // 测试4: 搜索摘要
            System.out.println("\n4. 搜索摘要测试");
            SearchSummary summary = webSearch.getSearchSummary("OpenJDK");
            System.out.println("搜索摘要:");
            System.out.println("查询词: " + summary.getOriginalQuery());
            System.out.println("估计匹配数: " + summary.getTotalEstimatedMatches());
            System.out.println("实际结果数: " + summary.getActualResults());
            System.out.println("主要域名: " + summary.getTopDomains());
            System.out.println("组合摘要: " + summary.getCombinedSnippets().substring(0, 
                Math.min(200, summary.getCombinedSnippets().length())) + "...");
            
            System.out.println("\n=== 所有测试完成 ===");
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 