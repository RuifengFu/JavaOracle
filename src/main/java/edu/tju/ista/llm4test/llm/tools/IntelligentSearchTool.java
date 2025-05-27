package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.logging.Level;

/**
 * 智能搜索工具 - 根据初始分析生成有针对性的搜索查询
 */
public class IntelligentSearchTool {
    private final OpenAI llm;
    private final BingSearch searchTool;
    private final WebContentExtractor webExtractor;
    private final ContentProcessor contentProcessor;
    private final ObjectMapper objectMapper;
    
    public IntelligentSearchTool(BingSearch searchTool, WebContentExtractor webExtractor, ContentProcessor contentProcessor) {
        this.llm = OpenAI.R1;
        this.searchTool = searchTool;
        this.webExtractor = webExtractor;
        this.contentProcessor = contentProcessor;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 根据初始分析生成智能搜索结果
     * @param initialAnalysis 初始分析JSON字符串
     * @param testCode 测试代码
     * @param testOutput 测试输出
     * @return 搜索到的相关内容列表
     */
    public List<IntelligentSearchResult> search(String initialAnalysis, String testCode, String testOutput) {
        try {
            // 1. 生成搜索查询
            List<SearchQuery> queries = generateSearchQueries(initialAnalysis, testCode, testOutput);
            LoggerUtil.logExec(Level.INFO, "生成了 " + queries.size() + " 个搜索查询");
            
            // 2. 执行搜索并收集结果
            List<IntelligentSearchResult> allResults = new ArrayList<>();
            for (SearchQuery query : queries) {
                List<IntelligentSearchResult> queryResults = executeSearch(query, initialAnalysis);
                allResults.addAll(queryResults);
            }
            
            // 3. 去重和排序
            allResults = deduplicateAndRank(allResults);
            LoggerUtil.logExec(Level.INFO, "搜索完成，共收集到 " + allResults.size() + " 个相关结果");
            
            return allResults;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "智能搜索失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 根据分析上下文生成搜索查询
     */
    private List<SearchQuery> generateSearchQueries(String initialAnalysis, String testCode, String testOutput) {
        String prompt = """
                你是一个技术搜索专家。根据测试用例失败的初始分析，生成有针对性的搜索查询。
                
                初始分析:
                %s
                
                测试代码:
                %s
                
                测试输出:
                %s
                
                请生成3-6个搜索查询，每个查询都应该针对问题的不同方面：
                1. API文档和规范相关的查询
                2. 已知bug和issue相关的查询
                3. 实现细节和源码相关的查询
                4. 最佳实践和使用示例相关的查询
                5. 版本兼容性和变更相关的查询
                6. 测试和验证相关的查询
                
                每个查询要包含：
                - 搜索关键词（英文，适合搜索引擎）
                - 查询目标（希望找到什么信息）
                - 优先级（1-10，10最高）
                - 预期的内容类型（官方文档、博客、Stack Overflow等）
                
                输出格式:
                {
                  "queries": [
                    {
                      "keywords": "Java HashMap thread safety concurrent modification",
                      "target": "查找HashMap线程安全相关的官方文档和规范",
                      "priority": 9,
                      "expected_types": ["官方文档", "API文档"]
                    },
                    {
                      "keywords": "HashMap ConcurrentModificationException bug JDK",
                      "target": "查找相关的已知bug报告和解决方案",
                      "priority": 8,
                      "expected_types": ["bug报告", "issue tracker", "Stack Overflow"]
                    }
                  ]
                }
                """.formatted(initialAnalysis, testCode, testOutput);
        
        try {
            String response = llm.messageCompletion(prompt);
            return parseSearchQueries(response);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "生成搜索查询失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 解析搜索查询响应
     */
    private List<SearchQuery> parseSearchQueries(String response) {
        List<SearchQuery> queries = new ArrayList<>();
        
        try {
            String cleanedResponse = extractJsonFromResponse(response);
            JsonNode root = objectMapper.readTree(cleanedResponse);
            JsonNode queriesNode = root.get("queries");
            
            if (queriesNode != null && queriesNode.isArray()) {
                for (JsonNode queryNode : queriesNode) {
                    String keywords = queryNode.get("keywords").asText();
                    String target = queryNode.get("target").asText();
                    int priority = queryNode.get("priority").asInt();
                    
                    List<String> expectedTypes = new ArrayList<>();
                    JsonNode typesNode = queryNode.get("expected_types");
                    if (typesNode != null && typesNode.isArray()) {
                        for (JsonNode typeNode : typesNode) {
                            expectedTypes.add(typeNode.asText());
                        }
                    }
                    
                    queries.add(new SearchQuery(keywords, target, priority, expectedTypes));
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析搜索查询失败: " + e.getMessage());
        }
        
        return queries;
    }
    
    /**
     * 执行单个搜索查询
     */
    private List<IntelligentSearchResult> executeSearch(SearchQuery query, String analysisContext) {
        List<IntelligentSearchResult> results = new ArrayList<>();
        
        try {
            // 执行搜索
            ToolResponse<List<SearchResult>> searchResponse = searchTool.execute(query.getKeywords());
            if (!searchResponse.isSuccess() || searchResponse.getResult().isEmpty()) {
                LoggerUtil.logExec(Level.INFO, "搜索无结果: " + query.getKeywords());
                return results;
            }
            
            // 限制处理的结果数量
            List<SearchResult> searchResults = searchResponse.getResult();
            int maxResults = Math.min(3, searchResults.size());
            
            for (int i = 0; i < maxResults; i++) {
                SearchResult searchResult = searchResults.get(i);
                try {
                    // 获取网页内容
                    ToolResponse<String> contentResponse = webExtractor.execute(searchResult.getUrl());
                    if (contentResponse.isSuccess()) {
                        // 处理内容
                        List<ContentProcessor.ProcessedContentChunk> chunks = 
                            contentProcessor.processContent(searchResult.getUrl(), 
                                                          contentResponse.getResult(), 
                                                          analysisContext);
                        
                        if (!chunks.isEmpty()) {
                            IntelligentSearchResult intelligentResult = new IntelligentSearchResult(
                                searchResult.getUrl(),
                                searchResult.getTitle(),
                                searchResult.getSnippet(),
                                chunks,
                                query.getPriority(),
                                query.getTarget(),
                                calculateRelevanceScore(chunks)
                            );
                            results.add(intelligentResult);
                        }
                    }
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.FINE, "处理搜索结果失败: " + searchResult.getUrl() + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "执行搜索失败: " + query.getKeywords() + " - " + e.getMessage());
        }
        
        return results;
    }
    
    /**
     * 计算整体相关性分数
     */
    private double calculateRelevanceScore(List<ContentProcessor.ProcessedContentChunk> chunks) {
        if (chunks.isEmpty()) return 0.0;
        
        double totalScore = 0.0;
        double totalWeight = 0.0;
        
        for (ContentProcessor.ProcessedContentChunk chunk : chunks) {
            double weight = chunk.getKeyPoints().size() + 1; // 关键点越多权重越高
            totalScore += chunk.getRelevanceScore() * weight;
            totalWeight += weight;
        }
        
        return totalWeight > 0 ? totalScore / totalWeight : 0.0;
    }
    
    /**
     * 去重和排序结果
     */
    private List<IntelligentSearchResult> deduplicateAndRank(List<IntelligentSearchResult> results) {
        // 按URL去重
        Map<String, IntelligentSearchResult> uniqueResults = new HashMap<>();
        for (IntelligentSearchResult result : results) {
            String key = result.getUrl();
            if (!uniqueResults.containsKey(key) || 
                uniqueResults.get(key).getOverallRelevanceScore() < result.getOverallRelevanceScore()) {
                uniqueResults.put(key, result);
            }
        }
        
        // 排序：先按优先级，再按相关性分数
        List<IntelligentSearchResult> sortedResults = new ArrayList<>(uniqueResults.values());
        sortedResults.sort((a, b) -> {
            int priorityComparison = Integer.compare(b.getQueryPriority(), a.getQueryPriority());
            if (priorityComparison != 0) return priorityComparison;
            return Double.compare(b.getOverallRelevanceScore(), a.getOverallRelevanceScore());
        });
        
        return sortedResults;
    }
    
    /**
     * 从响应中提取JSON内容
     */
    private String extractJsonFromResponse(String response) {
        response = response.replaceAll("```json\\n", "").replaceAll("```", "");
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}') + 1;
        if (start >= 0 && end > start) {
            return response.substring(start, end);
        }
        return response;
    }
    
    /**
     * 搜索查询类
     */
    public static class SearchQuery {
        private String keywords;
        private String target;
        private int priority;
        private List<String> expectedTypes;
        
        public SearchQuery(String keywords, String target, int priority, List<String> expectedTypes) {
            this.keywords = keywords;
            this.target = target;
            this.priority = priority;
            this.expectedTypes = expectedTypes != null ? expectedTypes : new ArrayList<>();
        }
        
        // Getters
        public String getKeywords() { return keywords; }
        public String getTarget() { return target; }
        public int getPriority() { return priority; }
        public List<String> getExpectedTypes() { return expectedTypes; }
    }
    
    /**
     * 智能搜索结果类
     */
    public static class IntelligentSearchResult {
        private String url;
        private String title;
        private String snippet;
        private List<ContentProcessor.ProcessedContentChunk> processedChunks;
        private int queryPriority;
        private String queryTarget;
        private double overallRelevanceScore;
        
        public IntelligentSearchResult(String url, String title, String snippet,
                                     List<ContentProcessor.ProcessedContentChunk> processedChunks,
                                     int queryPriority, String queryTarget, double overallRelevanceScore) {
            this.url = url;
            this.title = title;
            this.snippet = snippet;
            this.processedChunks = processedChunks != null ? processedChunks : new ArrayList<>();
            this.queryPriority = queryPriority;
            this.queryTarget = queryTarget;
            this.overallRelevanceScore = overallRelevanceScore;
        }
        
        // Getters
        public String getUrl() { return url; }
        public String getTitle() { return title; }
        public String getSnippet() { return snippet; }
        public List<ContentProcessor.ProcessedContentChunk> getProcessedChunks() { return processedChunks; }
        public int getQueryPriority() { return queryPriority; }
        public String getQueryTarget() { return queryTarget; }
        public double getOverallRelevanceScore() { return overallRelevanceScore; }
        
        /**
         * 获取格式化的结果内容
         */
        public String getFormattedContent() {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(title).append("\n");
            sb.append("**来源**: ").append(url).append("\n");
            sb.append("**查询目标**: ").append(queryTarget).append("\n");
            sb.append("**相关性**: ").append(String.format("%.2f", overallRelevanceScore)).append("\n\n");
            
            sb.append("## 搜索摘要\n").append(snippet).append("\n\n");
            
            if (!processedChunks.isEmpty()) {
                sb.append("## 处理后的内容片段\n\n");
                for (ContentProcessor.ProcessedContentChunk chunk : processedChunks) {
                    sb.append(chunk.getFormattedContent()).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
} 