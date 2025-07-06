package edu.tju.ista.llm4test.utils.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.websearch.api.*;
import static edu.tju.ista.llm4test.utils.websearch.api.ApiModels.*;
import static edu.tju.ista.llm4test.utils.websearch.api.RerankModels.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;

/**
 * 提供Web搜索、图片搜索和结果重排序功能。
 */
public class WebSearch {
    
    private static final String API_BASE_URL = GlobalConfig.getWebSearchApiBaseUrl();
    private static final String RERANK_API_URL = GlobalConfig.getWebSearchRerankApiUrl();
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    
    private final SearchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public WebSearch() {
        this.config = new SearchConfig().setApiKey(GlobalConfig.getBochaApiKey()).setSummary(true);
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
    
    /**
     * 对搜索结果进行重新排序
     * @param query 查询词
     * @param results 需要排序的搜索结果列表
     * @return 重新排序后的搜索结果列表
     */
    public List<SearchResult> rerank(String query, List<SearchResult> results) {
        return rerank(query, results, null);
    }

    /**
     * 对搜索结果进行重新排序
     * @param query 查询词
     * @param results 需要排序的搜索结果列表
     * @param topN 返回的Top文档数量
     * @return 重新排序后的搜索结果列表
     */
    public List<SearchResult> rerank(String query, List<SearchResult> results, Integer topN) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("查询关键词不能为空");
        }
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        if (config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            throw new IllegalArgumentException("API Key不能为空，请在SearchConfig中设置API Key");
        }

        try {
            RerankRequest rerankRequest = new RerankRequest();
            rerankRequest.model = "gte-rerank";
            rerankRequest.query = query;
            rerankRequest.documents = results.stream().map(SearchResult::getSnippet).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            rerankRequest.topN = topN;
            rerankRequest.returnDocuments = false;

            String requestBody = objectMapper.writeValueAsString(rerankRequest);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(RERANK_API_URL))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .timeout(Duration.ofSeconds(config.getTimeout()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                RerankApiResponse rerankApiResponse = objectMapper.readValue(response.body(), RerankApiResponse.class);
                if (rerankApiResponse.code == 200 && rerankApiResponse.data != null && rerankApiResponse.data.results != null) {
                    List<SearchResult> rerankedResults = new ArrayList<>();
                    for (RerankResult rerankResult : rerankApiResponse.data.results) {
                        if (rerankResult.index >= 0 && rerankResult.index < results.size()) {
                            SearchResult originalResult = results.get(rerankResult.index);
                            SearchResult newResult = originalResult;
                            newResult.setRelevanceScore(rerankResult.relevanceScore);
                            rerankedResults.add(newResult);
                        }
                    }
                    return rerankedResults;
                } else {
                    throw new RuntimeException("Rerank API返回错误: " + rerankApiResponse.msg);
                }
            } else {
                throw new RuntimeException("Rerank HTTP请求失败，状态码: " + response.statusCode() + ", 响应: " + response.body());
            }

        } catch (IOException | InterruptedException e) {
            if (config.isEnableLogging()) {
                LoggerUtil.logOpenAI(Level.SEVERE, "Rerank失败: " + e.getMessage());
            }
            throw new RuntimeException("Rerank请求失败: " + e.getMessage(), e);
        }
    }
    
    // 测试方法
    public static void main(String[] args) {
        try {
            System.out.println("=== WebSearch API 测试 ===");
            
            // 注意：需要设置真实的API Key才能进行测试
            String apiKey = GlobalConfig.getBochaApiKey(); // 从配置获取API Key
            
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.out.println("请在config.properties或环境变量中设置 BOCHA_API_KEY");
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
            
            // 测试5: Rerank功能
            System.out.println("\n5. Rerank功能测试");
            if (!results.isEmpty()) {
                List<SearchResult> rerankedResults = webSearch.rerank("JTreg是什么", results, 3);
                System.out.println("对'JTreg是什么'的查询进行Rerank (Top 3):");
                for (SearchResult result : rerankedResults) {
                    System.out.printf("- [分数: %.4f] %s%n", result.getRelevanceScore(), result.getName());
                    System.out.println("  " + result.getUrl());
                }
            } else {
                System.out.println("无搜索结果可用于Rerank测试。");
            }
            
            System.out.println("\n=== 所有测试完成 ===");
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 