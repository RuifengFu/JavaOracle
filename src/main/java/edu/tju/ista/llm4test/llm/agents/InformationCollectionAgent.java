package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.websearch.SearchConfig;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 信息收集Agent - 使用简化工具收集相关信息并进行重排序
 * 目标：收集最相关的信息，总大小控制在32k以内
 */
public class InformationCollectionAgent extends Agent {
    
    private final SimplifiedSourceCodeSearchTool sourceTool;
    private final SimplifiedJavaDocSearchTool javadocTool;
    private final BochaSearch webSearchTool;
    private final ReRankerTool rerankerTool;
    // 不再将WebContentExtractor作为字段，而是在需要时创建并及时关闭
    private final OpenAI llm;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    
    // 信息收集配置
    private static final int MAX_TOTAL_SIZE = 32000; // 32k字符限制
    private static final int MAX_SEARCH_RESULTS = 10; // 每次搜索的最大结果数
    private static final int MAX_RERANK_RESULTS = 5; // 重排序后保留的结果数
    private static final int MAX_WEB_EXTRACTIONS = 5; // 最大Web内容提取数量
    
    // 信息收集状态
    private final List<CollectedInfo> collectedInfos = new ArrayList<>();
    private int currentSize = 0;
    
    public InformationCollectionAgent(String sourcePath, String javadocPath) {
        super("你是一个信息收集专家，负责收集与Bug分析相关的最重要信息。");
        this.sourceTool = new SimplifiedSourceCodeSearchTool(sourcePath);
        this.javadocTool = new SimplifiedJavaDocSearchTool(javadocPath);
        
        // 创建Web搜索工具，禁用内部日志记录
        String apiKey = System.getenv("BOCHA_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            SearchConfig config = new SearchConfig()
                .setApiKey(apiKey)
                .setSummary(true)
                .setEnableLogging(false); // 禁用内部日志记录
            this.webSearchTool = new BochaSearch(config);
        } else {
            this.webSearchTool = new BochaSearch(); // 使用默认配置
        }
        
        this.rerankerTool = new ReRankerTool(new ArrayList<>());
        this.llm = OpenAI.V3;
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(8);
    }
    
    /**
     * 收集相关信息
     */
    public List<CollectedInfo> collectInformation(String initialInsight, String testCode, 
                                                 String testOutput, String apiInfoWithSource) {
        LoggerUtil.logExec(Level.INFO, "开始信息收集流程");
        
        try {
            // 重置状态
            collectedInfos.clear();
            currentSize = 0;
            
            // 1. 分析初始洞察
            AnalysisResult analysis = analyzeInitialInsight(initialInsight);
            LoggerUtil.logExec(Level.INFO, String.format("分析结果: %d个相关类, %d个查询建议", 
                analysis.relevantClasses.size(), analysis.queries.size()));
            
            // 2. 并行收集不同类型的信息
            List<CompletableFuture<Void>> collectionTasks = new ArrayList<>();
            
            // 2.1 收集源码信息
            collectionTasks.add(CompletableFuture.runAsync(() -> {
                try {
                    LoggerUtil.logExec(Level.INFO, "收集源码信息");
                    collectSourceCodeInfo(analysis, testCode);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "收集源码信息失败: " + e.getMessage());
                }
            }, executorService));
            
            // 2.2 收集JavaDoc信息
            collectionTasks.add(CompletableFuture.runAsync(() -> {
                try {
                    LoggerUtil.logExec(Level.INFO, "收集JavaDoc信息");
                    collectJavaDocInfo(analysis, testCode);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "收集JavaDoc信息失败: " + e.getMessage());
                }
            }, executorService));
            
            // 2.3 收集Web搜索信息（可能较慢，独立处理）
            collectionTasks.add(CompletableFuture.runAsync(() -> {
                try {
                    LoggerUtil.logExec(Level.INFO, "收集Web搜索信息");
                    collectWebSearchInfo(analysis, testOutput);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "收集Web搜索信息失败: " + e.getMessage());
                }
            }, executorService));
            
            // 等待所有收集任务完成，但有超时限制
            try {
                CompletableFuture.allOf(collectionTasks.toArray(new CompletableFuture[0]))
                    .get(120, java.util.concurrent.TimeUnit.SECONDS); // 2分钟超时
            } catch (java.util.concurrent.TimeoutException e) {
                LoggerUtil.logExec(Level.WARNING, "信息收集超时，使用已收集的信息");
                // 取消未完成的任务
                for (CompletableFuture<Void> task : collectionTasks) {
                    task.cancel(true);
                }
            }
            
            // 3. 添加API信息（如果有）
            if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
                try {
                    String truncatedApiInfo = apiInfoWithSource.length() > 8000 ? 
                        apiInfoWithSource.substring(0, 8000) + "...(API信息已截断)" : apiInfoWithSource;
                    
                    CollectedInfo apiInfo = new CollectedInfo(
                        "API_INFO",
                        "测试用例API信息和源码",
                        truncatedApiInfo,
                        InfoType.SOURCE_CODE,
                        0.8 // 高相关性
                    );
                    
                    addInfoIfSpaceAvailable(apiInfo);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "添加API信息失败: " + e.getMessage());
                }
            }
            
            // 4. 重排序和筛选
            List<CollectedInfo> finalInfos = rerankAndFilter(analysis, testCode, testOutput);
            
            // 5. 计算最终统计信息
            int totalSize = calculateTotalSize(finalInfos);
            LoggerUtil.logExec(Level.INFO, String.format("信息收集完成，共收集 %d 条信息，总大小: %d 字符", 
                finalInfos.size(), totalSize));
            
            return finalInfos;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "信息收集过程中发生严重错误: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * 分析初始洞察，提取关键信息
     */
    private AnalysisResult analyzeInitialInsight(String initialInsight) {
        AnalysisResult result = new AnalysisResult();
        
        try {
            LoggerUtil.logExec(Level.INFO, "开始分析初始洞察，内容长度: " + initialInsight.length());
            
            JsonNode rootNode = objectMapper.readTree(initialInsight);
            
            // 提取症状
            JsonNode symptomsNode = rootNode.path("symptoms");
            if (!symptomsNode.isMissingNode()) {
                result.symptoms = symptomsNode.asText();
                LoggerUtil.logExec(Level.INFO, "提取症状: " + result.symptoms);
            } else {
                LoggerUtil.logExec(Level.WARNING, "未找到症状信息");
            }
            
            // 提取相关类
            JsonNode classesNode = rootNode.path("relevantClasses");
            if (classesNode.isArray()) {
                for (JsonNode classNode : classesNode) {
                    String className = classNode.asText();
                    if (className != null && !className.trim().isEmpty()) {
                        result.relevantClasses.add(className);
                        LoggerUtil.logExec(Level.INFO, "添加相关类: " + className);
                    }
                }
            } else {
                LoggerUtil.logExec(Level.WARNING, "未找到相关类数组或格式错误");
            }
            
            // 提取错误位置
            JsonNode errorLocationNode = rootNode.path("errorLocation");
            if (!errorLocationNode.isMissingNode()) {
                result.errorLocation = errorLocationNode.asText();
                LoggerUtil.logExec(Level.INFO, "提取错误位置: " + result.errorLocation);
            } else {
                LoggerUtil.logExec(Level.WARNING, "未找到错误位置信息");
            }
            
            // 提取查询建议
            JsonNode queriesNode = rootNode.path("queries");
            if (queriesNode.isArray()) {
                for (JsonNode queryNode : queriesNode) {
                    String query = queryNode.asText();
                    if (query != null && !query.trim().isEmpty()) {
                        result.queries.add(query);
                        LoggerUtil.logExec(Level.INFO, "添加查询: " + query);
                    }
                }
            } else {
                LoggerUtil.logExec(Level.WARNING, "未找到查询数组或格式错误");
            }
            
            LoggerUtil.logExec(Level.INFO, String.format("分析完成: %d个相关类, %d个查询建议", 
                result.relevantClasses.size(), result.queries.size()));
            
            // 如果没有提取到有用信息，记录详细的JSON结构
            if (result.relevantClasses.isEmpty() && result.queries.isEmpty()) {
                LoggerUtil.logExec(Level.WARNING, "未提取到任何有用信息，JSON结构如下:");
                LoggerUtil.logExec(Level.WARNING, "原始JSON: " + initialInsight);
                LoggerUtil.logExec(Level.WARNING, "解析后的root节点: " + rootNode.toString());
            }
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "解析初始洞察失败: " + e.getMessage());
            LoggerUtil.logExec(Level.SEVERE, "原始JSON内容: " + initialInsight);
            e.printStackTrace();
        }
        
        return result;
    }
    
    /**
     * 收集源码信息
     */
    private void collectSourceCodeInfo(AnalysisResult analysis, String testCode) {
        LoggerUtil.logExec(Level.INFO, "收集源码信息");
        
        // 1. 按类名搜索相关类的源码
        for (String className : analysis.relevantClasses) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            ToolResponse<String> response = sourceTool.execute(Map.of(
                "search_type", "by_class",
                "class_name", className
            ));
            
            if (response.isSuccess()) {
                String content = response.getResult();
                if (content.length() > 5000) { // 截断过长的内容
                    content = content.substring(0, 5000) + "...(内容已截断)";
                }
                
                CollectedInfo info = new CollectedInfo(
                    "SOURCE_CLASS_" + className,
                    "源码: " + className,
                    content,
                    InfoType.SOURCE_CODE,
                    calculateRelevanceScore(content, analysis.symptoms)
                );
                
                addInfoIfSpaceAvailable(info);
            }
        }
        
        // 2. 搜索相关方法实现
        for (String className : analysis.relevantClasses) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            // 从症状中提取可能的方法名
            List<String> possibleMethods = extractMethodNamesFromSymptoms(analysis.symptoms);
            
            for (String methodName : possibleMethods) {
                if (currentSize >= MAX_TOTAL_SIZE) break;
                
                ToolResponse<String> response = sourceTool.execute(Map.of(
                    "search_type", "by_method",
                    "class_name", className,
                    "method_name", methodName
                ));
                
                if (response.isSuccess()) {
                    String content = response.getResult();
                    if (content.length() > 3000) {
                        content = content.substring(0, 3000) + "...(内容已截断)";
                    }
                    
                    CollectedInfo info = new CollectedInfo(
                        "SOURCE_METHOD_" + className + "_" + methodName,
                        "方法源码: " + className + "." + methodName,
                        content,
                        InfoType.SOURCE_CODE,
                        calculateRelevanceScore(content, analysis.symptoms)
                    );
                    
                    addInfoIfSpaceAvailable(info);
                }
            }
        }
        
        // 3. 关键词搜索
        for (String query : analysis.queries) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            ToolResponse<String> response = sourceTool.execute(Map.of(
                "search_type", "by_keyword",
                "keyword", query
            ));
            
            if (response.isSuccess()) {
                String content = response.getResult();
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + "...(内容已截断)";
                }
                
                CollectedInfo info = new CollectedInfo(
                    "SOURCE_KEYWORD_" + query.replaceAll("\\s+", "_"),
                    "关键词搜索: " + query,
                    content,
                    InfoType.SOURCE_CODE,
                    calculateRelevanceScore(content, analysis.symptoms)
                );
                
                addInfoIfSpaceAvailable(info);
            }
        }
        
        // 4. 查找相关类
        for (String className : analysis.relevantClasses.subList(0, Math.min(2, analysis.relevantClasses.size()))) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            ToolResponse<String> response = sourceTool.execute(Map.of(
                "search_type", "related_classes",
                "class_name", className
            ));
            
            if (response.isSuccess()) {
                String content = response.getResult();
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...(内容已截断)";
                }
                
                CollectedInfo info = new CollectedInfo(
                    "SOURCE_RELATED_" + className,
                    "相关类: " + className,
                    content,
                    InfoType.SOURCE_CODE,
                    calculateRelevanceScore(content, analysis.symptoms)
                );
                
                addInfoIfSpaceAvailable(info);
            }
        }
    }
    
    /**
     * 收集JavaDoc信息
     */
    private void collectJavaDocInfo(AnalysisResult analysis, String testCode) {
        LoggerUtil.logExec(Level.INFO, "收集JavaDoc信息");
        
        // 1. 按类名搜索API文档
        for (String className : analysis.relevantClasses) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            ToolResponse<String> response = javadocTool.execute(Map.of(
                "search_type", "by_class",
                "class_name", className
            ));
            
            if (response.isSuccess()) {
                String content = response.getResult();
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + "...(内容已截断)";
                }
                
                CollectedInfo info = new CollectedInfo(
                    "JAVADOC_CLASS_" + className,
                    "API文档: " + className,
                    content,
                    InfoType.JAVADOC,
                    calculateRelevanceScore(content, analysis.symptoms)
                );
                
                addInfoIfSpaceAvailable(info);
            }
        }
        
        // 2. 按包名浏览相关包
        Set<String> packages = analysis.relevantClasses.stream()
            .filter(cls -> cls.contains("."))
            .map(cls -> cls.substring(0, cls.lastIndexOf('.')))
            .collect(Collectors.toSet());
        
        for (String packageName : packages) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            ToolResponse<String> response = javadocTool.execute(Map.of(
                "search_type", "by_package",
                "package_name", packageName
            ));
            
            if (response.isSuccess()) {
                String content = response.getResult();
                if (content.length() > 3000) {
                    content = content.substring(0, 3000) + "...(内容已截断)";
                }
                
                CollectedInfo info = new CollectedInfo(
                    "JAVADOC_PACKAGE_" + packageName,
                    "包文档: " + packageName,
                    content,
                    InfoType.JAVADOC,
                    calculateRelevanceScore(content, analysis.symptoms)
                );
                
                addInfoIfSpaceAvailable(info);
            }
        }
    }
    
    /**
     * 收集Web搜索信息（异步处理）
     */
    private void collectWebSearchInfo(AnalysisResult analysis, String testOutput) {
        LoggerUtil.logExec(Level.INFO, "收集Web搜索信息");
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 1. 基于症状搜索
        if (analysis.symptoms != null && !analysis.symptoms.isEmpty()) {
            if (currentSize < MAX_TOTAL_SIZE) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    processWebSearch(analysis.symptoms + " Java bug", "WEB_SYMPTOMS", "Web搜索: " + analysis.symptoms, analysis.symptoms);
                }, executorService);
                futures.add(future);
            }
        }
        
        // 2. 基于查询建议搜索
        for (int i = 0; i < Math.min(3, analysis.queries.size()); i++) {
            String query = analysis.queries.get(i);
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                processWebSearch(query + " Java", "WEB_QUERY_" + query.replaceAll("\\s+", "_"), "Web搜索: " + query, analysis.symptoms);
            }, executorService);
            futures.add(future);
        }
        
        // 3. 基于错误信息搜索
        if (testOutput != null && !testOutput.isEmpty()) {
            if (currentSize < MAX_TOTAL_SIZE) {
                String errorKeywords = extractErrorKeywords(testOutput);
                if (!errorKeywords.isEmpty()) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        processWebSearch(errorKeywords + " Java exception", "WEB_ERROR", "Web搜索: " + errorKeywords, analysis.symptoms);
                    }, executorService);
                    futures.add(future);
                }
            }
        }
        
        // 等待所有异步任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    
    /**
     * 处理单个Web搜索并提取内容
     */
    private void processWebSearch(String query, String id, String source, String symptoms) {
        try {
            // 清理查询字符串，避免重复
            String cleanedQuery = query.trim().replaceAll("\\s+", " ");
            if (cleanedQuery.length() > 200) {
                cleanedQuery = cleanedQuery.substring(0, 200);
            }
            
            LoggerUtil.logExec(Level.INFO, "开始搜索: " + cleanedQuery);
            
            ToolResponse<List<SearchResult>> response = webSearchTool.executeForResults(Map.of(
                "query", cleanedQuery,
                "max_results", MAX_SEARCH_RESULTS
            ));
            
            if (response.isSuccess()) {
                List<SearchResult> searchResults = response.getResult();
                LoggerUtil.logExec(Level.INFO, "搜索完成，获得 " + searchResults.size() + " 个结果");
                
                // 构建搜索结果概览
                StringBuilder searchResultsText = new StringBuilder();
                for (SearchResult result : searchResults) {
                    searchResultsText.append(String.format("标题: %s\nURL: %s\n摘要: %s\n\n", 
                        result.getName(), result.getUrl(), result.getSnippet()));
                }
                
                // 异步提取前几个URL的内容，但有超时和错误处理
                List<CompletableFuture<String>> extractionFutures = new ArrayList<>();
                int validUrls = 0;
                
                for (int i = 0; i < Math.min(MAX_WEB_EXTRACTIONS, searchResults.size()); i++) {
                    SearchResult result = searchResults.get(i);
                    String url = result.getUrl();
                    
                    // 跳过可能有问题的URL
                    if (url == null || url.isEmpty() || 
                        url.contains("javascript:") || 
                        url.contains("mailto:") ||
                        url.length() > 500) {
                        LoggerUtil.logExec(Level.WARNING, "跳过无效URL: " + url);
                        continue;
                    }
                    
                    validUrls++;
                    LoggerUtil.logExec(Level.INFO, "准备提取内容，URL: " + url);
                    
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        WebContentExtractor extractor = null;
                        try {
                            // 为每个任务创建独立的WebContentExtractor
                            extractor = new WebContentExtractor(true);
                            ToolResponse<String> extractResponse = extractor.execute(url);
                            if (extractResponse.isSuccess()) {
                                String content = extractResponse.getResult();
                                LoggerUtil.logExec(Level.INFO, "成功提取内容，长度: " + content.length());
                                
                                // 截断过长的内容
                                if (content.length() > 3000) {
                                    content = content.substring(0, 3000) + "...(Web内容已截断)";
                                }
                                
                                // 保存提取的内容到SearchResult对象
                                result.setContent(content);
                                
                                return String.format("标题: %s\nURL: %s\n内容:\n%s", 
                                    result.getName(), url, content);
                            } else {
                                LoggerUtil.logExec(Level.WARNING, "提取Web内容失败: " + url + " - " + extractResponse.getFailMessage());
                            }
                        } catch (Exception e) {
                            LoggerUtil.logExec(Level.WARNING, "提取Web内容异常: " + url + " - " + e.getMessage());
                        } finally {
                            // 确保在任务完成后关闭WebContentExtractor
                            if (extractor != null) {
                                try {
                                    extractor.close();
                                } catch (Exception e) {
                                    LoggerUtil.logExec(Level.WARNING, "关闭WebContentExtractor失败: " + e.getMessage());
                                }
                            }
                        }
                        return null;
                    }, executorService).orTimeout(30, java.util.concurrent.TimeUnit.SECONDS); // 30秒超时
                    
                    extractionFutures.add(future);
                }
                
                LoggerUtil.logExec(Level.INFO, "准备提取 " + validUrls + " 个有效URL的内容");
                
                // 收集所有提取的内容，带超时处理
                StringBuilder allContent = new StringBuilder();
                allContent.append("搜索结果概览:\n").append(searchResultsText.toString()).append("\n\n");
                allContent.append("详细内容:\n");
                
                int successfulExtractions = 0;
                for (CompletableFuture<String> future : extractionFutures) {
                    try {
                        String content = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                        if (content != null) {
                            allContent.append(content).append("\n\n---\n\n");
                            successfulExtractions++;
                        }
                    } catch (java.util.concurrent.TimeoutException e) {
                        LoggerUtil.logExec(Level.WARNING, "Web内容提取超时");
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "获取Web提取结果失败: " + e.getMessage());
                    }
                }
                
                LoggerUtil.logExec(Level.INFO, "成功提取 " + successfulExtractions + " 个Web内容");
                
                String finalContent = allContent.toString();
                if (finalContent.length() > 5000) {
                    finalContent = finalContent.substring(0, 5000) + "...(Web搜索内容已截断)";
                }
                
                // 计算相关性得分
                double relevanceScore = calculateRelevanceScore(finalContent, symptoms);
                LoggerUtil.logExec(Level.INFO, "计算相关性得分: " + relevanceScore);
                
                synchronized (this) {
                    CollectedInfo info = new CollectedInfo(
                        id,
                        source,
                        finalContent,
                        InfoType.WEB_SEARCH,
                        relevanceScore
                    );
                    
                    boolean added = tryAddInfo(info);
                    if (added) {
                        LoggerUtil.logExec(Level.INFO, "成功添加Web搜索信息: " + info.source);
                    } else {
                        LoggerUtil.logExec(Level.WARNING, "Web搜索信息被拒绝，可能是因为空间不足");
                    }
                }
            } else {
                LoggerUtil.logExec(Level.WARNING, "Web搜索失败: " + cleanedQuery + " - " + response.getFailMessage());
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Web搜索处理失败: " + query + " - " + e.getMessage());
            e.printStackTrace();
        }

    }
    
    /**
     * 尝试添加信息，返回是否成功
     */
    private boolean tryAddInfo(CollectedInfo info) {
        if (currentSize + info.content.length() <= MAX_TOTAL_SIZE) {
            collectedInfos.add(info);
            currentSize += info.content.length();
            return true;
        }
        return false;
    }
    
    /**
     * 重排序和筛选最重要的信息
     */
    private List<CollectedInfo> rerankAndFilter(AnalysisResult analysis, String testCode, String testOutput) {
        LoggerUtil.logExec(Level.INFO, "重排序和筛选信息");
        
        // 1. 按相关性得分排序
        collectedInfos.sort((a, b) -> Double.compare(b.relevanceScore, a.relevanceScore));
        
        // 2. 使用ReRanker进一步优化排序
        try {
            String queryContext = buildQueryContext(analysis, testCode, testOutput);
            
            // 将CollectedInfo转换为SearchResult
            List<SearchResult> searchResults = collectedInfos.stream()
                .map(info -> {
                    SearchResult result = new SearchResult(info.source, info.content, "", 0);
                    return result;
                })
                .collect(Collectors.toList());
            
            rerankerTool.setSearchResults(searchResults);
            ToolResponse<List<SearchResult>> rerankedResponse = rerankerTool.execute(Map.of(
                "query", queryContext
            ));
            
            if (rerankedResponse.isSuccess()) {
                List<SearchResult> rerankedResults = rerankedResponse.getResult();
                
                // 重新排序collectedInfos
                Map<String, CollectedInfo> infoMap = collectedInfos.stream()
                    .collect(Collectors.toMap(info -> info.source, info -> info));
                
                List<CollectedInfo> rerankedInfos = new ArrayList<>();
                for (SearchResult result : rerankedResults) {
                    CollectedInfo info = infoMap.get(result.getName());
                    if (info != null) {
                        rerankedInfos.add(info);
                    }
                }
                
                collectedInfos.clear();
                collectedInfos.addAll(rerankedInfos);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "重排序失败，使用原始排序: " + e.getMessage());
        }
        
        // 3. 筛选最终信息，确保总大小不超过限制
        List<CollectedInfo> finalInfos = new ArrayList<>();
        int totalSize = 0;
        
        for (CollectedInfo info : collectedInfos) {
            if (totalSize + info.content.length() > MAX_TOTAL_SIZE) {
                break;
            }
            finalInfos.add(info);
            totalSize += info.content.length();
        }
        
        return finalInfos;
    }
    
    /**
     * 添加信息（如果空间足够）
     */
    private synchronized void addInfoIfSpaceAvailable(CollectedInfo info) {
        boolean added = tryAddInfo(info);
        if (added) {
            LoggerUtil.logExec(Level.INFO, "成功添加信息: " + info.source + " (类型: " + info.type + ", 大小: " + info.content.length() + " 字符)");
        } else {
            LoggerUtil.logExec(Level.WARNING, "信息被拒绝，空间不足: " + info.source + " (需要: " + info.content.length() + " 字符, 剩余: " + (MAX_TOTAL_SIZE - currentSize) + " 字符)");
        }
    }
    
    /**
     * 计算相关性得分
     */
    private double calculateRelevanceScore(String content, String symptoms) {
        if (symptoms == null || symptoms.isEmpty()) return 0.5;
        
        String[] keywords = symptoms.toLowerCase().split("\\s+");
        String lowerContent = content.toLowerCase();
        
        int matches = 0;
        for (String keyword : keywords) {
            if (lowerContent.contains(keyword)) {
                matches++;
            }
        }
        
        return (double) matches / keywords.length;
    }
    
    /**
     * 从症状中提取可能的方法名
     */
    private List<String> extractMethodNamesFromSymptoms(String symptoms) {
        List<String> methods = new ArrayList<>();
        if (symptoms == null) return methods;
        
        // 常见的可能出现在错误信息中的方法名
        String[] commonMethods = {
            "put", "get", "remove", "add", "contains", "size", "isEmpty",
            "toString", "equals", "hashCode", "clone", "finalize",
            "next", "hasNext", "iterator", "forEach"
        };
        
        String lowerSymptoms = symptoms.toLowerCase();
        for (String method : commonMethods) {
            if (lowerSymptoms.contains(method)) {
                methods.add(method);
            }
        }
        
        return methods;
    }
    
    /**
     * 从测试输出中提取错误关键词
     */
    private String extractErrorKeywords(String testOutput) {
        if (testOutput == null || testOutput.isEmpty()) return "";
        
        // 提取异常类型和关键错误信息
        String[] lines = testOutput.split("\n");
        StringBuilder keywords = new StringBuilder();
        
        for (String line : lines) {
            if (line.contains("Exception") || line.contains("Error")) {
                // 提取异常类型
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.contains("Exception") || part.contains("Error")) {
                        keywords.append(part).append(" ");
                        break;
                    }
                }
                break;
            }
        }
        
        return keywords.toString().trim();
    }
    
    /**
     * 构建查询上下文
     */
    private String buildQueryContext(AnalysisResult analysis, String testCode, String testOutput) {
        StringBuilder context = new StringBuilder();
        
        if (analysis.symptoms != null) {
            context.append("症状: ").append(analysis.symptoms).append(" ");
        }
        
        if (analysis.errorLocation != null) {
            context.append("错误位置: ").append(analysis.errorLocation).append(" ");
        }
        
        if (!analysis.queries.isEmpty()) {
            context.append("查询: ").append(String.join(" ", analysis.queries));
        }
        
        return context.toString();
    }
    
    /**
     * 计算总大小
     */
    private int calculateTotalSize(List<CollectedInfo> infos) {
        return infos.stream().mapToInt(info -> info.content.length()).sum();
    }
    
    /**
     * 关闭资源
     */
    public void close() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }
    
    /**
     * 分析结果数据结构
     */
    private static class AnalysisResult {
        String symptoms = "";
        List<String> relevantClasses = new ArrayList<>();
        String errorLocation = "";
        List<String> queries = new ArrayList<>();
    }
    
    /**
     * 收集的信息数据结构
     */
    public static class CollectedInfo {
        public final String id;
        public final String source;
        public final String content;
        public final InfoType type;
        public final double relevanceScore;
        
        public CollectedInfo(String id, String source, String content, InfoType type, double relevanceScore) {
            this.id = id;
            this.source = source;
            this.content = content;
            this.type = type;
            this.relevanceScore = relevanceScore;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s (相关性: %.2f, 大小: %d)", 
                type, source, relevanceScore, content.length());
        }
    }
    
    /**
     * 信息类型枚举
     */
    public enum InfoType {
        SOURCE_CODE("源码"),
        JAVADOC("API文档"),
        WEB_SEARCH("Web搜索");
        
        private final String displayName;
        
        InfoType(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
} 