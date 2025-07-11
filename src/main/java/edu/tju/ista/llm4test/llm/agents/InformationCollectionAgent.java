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
import java.util.logging.Level;
import java.util.concurrent.*;

/**
 * 简化的信息收集Agent - 使用observe循环优化信息收集
 */
public class InformationCollectionAgent extends Agent {
    
    // 决策工具 - 确定性选择
    private static final Tool<Void> INFO_SUFFICIENT = new BasicTool("info_sufficient", "当前收集的信息足够分析测试失败原因");
    private static final Tool<Void> INFO_INSUFFICIENT = new BasicTool("info_insufficient", "当前收集的信息不足，需要更多信息");
    
    private final SimplifiedSourceCodeSearchTool sourceTool;
    private final SimplifiedJavaDocSearchTool javadocTool;
    private final BochaSearch webSearchTool;
    private final OpenAI llm;
    private final ObjectMapper objectMapper;
    
    // 信息收集配置
    private static final int MAX_TOTAL_SIZE = 32000;
    private static final int MAX_ITERATIONS = 3;
    
    // 当前收集状态
    private final List<CollectedInfo> collectedInfos = new ArrayList<>();
    private int currentSize = 0;
    
    public InformationCollectionAgent(String sourcePath, String javadocPath) {
        super("你是一个信息收集专家，负责收集与Bug分析相关的最重要信息。");
        
        // 初始化工具
        this.sourceTool = sourcePath != null ? new SimplifiedSourceCodeSearchTool(sourcePath) : null;
        this.javadocTool = javadocPath != null ? new SimplifiedJavaDocSearchTool(javadocPath) : null;
        
        // 初始化Web搜索工具
        String apiKey = System.getenv("BOCHA_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            SearchConfig config = new SearchConfig().setApiKey(apiKey).setSummary(true).setEnableLogging(false);
            this.webSearchTool = new BochaSearch(config);
        } else {
            this.webSearchTool = new BochaSearch();
        }
        
        this.llm = OpenAI.Doubao_think;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 收集相关信息 - 主入口方法
     */
    public List<CollectedInfo> collectInformation(String initialInsight, String testCode, 
                                                 String testOutput, String apiInfoWithSource) {
        LoggerUtil.logExec(Level.INFO, "开始信息收集流程");
        
        // 重置状态
        collectedInfos.clear();
        currentSize = 0;
        
        // 解析初始洞察
        AnalysisResult analysis = parseInitialInsight(initialInsight);
        
        // 观察循环 - 最多迭代3次
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            LoggerUtil.logExec(Level.INFO, "信息收集迭代 " + iteration + "/" + MAX_ITERATIONS);
            
            // 收集信息
            collectAllInformation(analysis, testCode, testOutput, apiInfoWithSource);
            
            // 观察和评估
            if (isInformationSufficient(analysis, testCode, testOutput, iteration)) {
                LoggerUtil.logExec(Level.INFO, "信息收集完成，在第 " + iteration + " 次迭代后满足要求");
                break;
            }
            
            // 如果不是最后一次迭代，重新分析
            if (iteration < MAX_ITERATIONS) {
                LoggerUtil.logExec(Level.INFO, "信息不足，准备第 " + (iteration + 1) + " 次迭代");
                analysis = refineAnalysis(analysis, testCode, testOutput);
                // 清空已收集信息，准备重新收集
                collectedInfos.clear();
                currentSize = 0;
            }
        }
        
        LoggerUtil.logExec(Level.INFO, String.format("信息收集完成，共收集 %d 条信息，总大小: %d 字符", 
            collectedInfos.size(), currentSize));
        
        // 输出完整的信息源内容
        outputDetailedReport();
        
        return new ArrayList<>(collectedInfos);
    }
    
    /**
     * 输出完整的信息源报告
     */
    private void outputDetailedReport() {
        LoggerUtil.logExec(Level.INFO, "================== 完整信息源报告 ==================");
        
        if (collectedInfos.isEmpty()) {
            LoggerUtil.logExec(Level.INFO, "未收集到任何信息");
            return;
        }
        
        // 按类型分组统计
        Map<InfoType, List<CollectedInfo>> groupedByType = new HashMap<>();
        for (CollectedInfo info : collectedInfos) {
            groupedByType.computeIfAbsent(info.type, k -> new ArrayList<>()).add(info);
        }
        
        // 输出统计信息
        LoggerUtil.logExec(Level.INFO, String.format("总计收集 %d 条信息，总大小 %d 字符", 
            collectedInfos.size(), currentSize));
        
        for (InfoType type : InfoType.values()) {
            List<CollectedInfo> infos = groupedByType.getOrDefault(type, new ArrayList<>());
            if (!infos.isEmpty()) {
                int totalSize = infos.stream().mapToInt(info -> info.content.length()).sum();
                LoggerUtil.logExec(Level.INFO, String.format("- %s: %d 条，共 %d 字符", 
                    type, infos.size(), totalSize));
            }
        }
        
        LoggerUtil.logExec(Level.INFO, "==================== 详细内容 ====================");
        
        // 输出每条信息的完整内容
        for (int i = 0; i < collectedInfos.size(); i++) {
            CollectedInfo info = collectedInfos.get(i);
            LoggerUtil.logExec(Level.INFO, String.format("\n--- 信息源 %d ---", i + 1));
            LoggerUtil.logExec(Level.INFO, "ID: " + info.id);
            LoggerUtil.logExec(Level.INFO, "类型: " + info.type);
            LoggerUtil.logExec(Level.INFO, "来源: " + info.source);
            LoggerUtil.logExec(Level.INFO, "相关性得分: " + String.format("%.3f", info.relevanceScore));
            LoggerUtil.logExec(Level.INFO, "内容大小: " + info.content.length() + " 字符");
            LoggerUtil.logExec(Level.INFO, "完整内容:\n" + info.content);
            LoggerUtil.logExec(Level.INFO, "--- 信息源 " + (i + 1) + " 结束 ---\n");
        }
        
        LoggerUtil.logExec(Level.INFO, "================= 信息源报告结束 =================");
    }
    
    /**
     * 获取格式化的详细报告字符串
     */
    public String getDetailedReport() {
        if (collectedInfos.isEmpty()) {
            return "# 信息收集报告\n\n未收集到任何信息。\n";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("# 信息收集详细报告\n\n");
        
        // 统计信息
        Map<InfoType, List<CollectedInfo>> groupedByType = new HashMap<>();
        for (CollectedInfo info : collectedInfos) {
            groupedByType.computeIfAbsent(info.type, k -> new ArrayList<>()).add(info);
        }
        
        report.append("## 统计信息\n\n");
        report.append(String.format("- **总计**: %d 条信息，%d 字符\n", collectedInfos.size(), currentSize));
        
        for (InfoType type : InfoType.values()) {
            List<CollectedInfo> infos = groupedByType.getOrDefault(type, new ArrayList<>());
            if (!infos.isEmpty()) {
                int totalSize = infos.stream().mapToInt(info -> info.content.length()).sum();
                report.append(String.format("- **%s**: %d 条，%d 字符\n", type, infos.size(), totalSize));
            }
        }
        
        report.append("\n## 详细内容\n\n");
        
        // 详细内容
        for (int i = 0; i < collectedInfos.size(); i++) {
            CollectedInfo info = collectedInfos.get(i);
            report.append(String.format("### 信息源 %d\n\n", i + 1));
            report.append(String.format("- **ID**: %s\n", info.id));
            report.append(String.format("- **类型**: %s\n", info.type));
            report.append(String.format("- **来源**: %s\n", info.source));
            report.append(String.format("- **相关性得分**: %.3f\n", info.relevanceScore));
            report.append(String.format("- **内容大小**: %d 字符\n\n", info.content.length()));
            report.append("**完整内容**:\n\n");
            report.append("```\n");
            report.append(info.content);
            report.append("\n```\n\n");
            report.append("---\n\n");
        }
        
        return report.toString();
    }
    
    /**
     * 解析初始洞察
     */
    private AnalysisResult parseInitialInsight(String initialInsight) {
        AnalysisResult result = new AnalysisResult();
        
        try {
            JsonNode rootNode = objectMapper.readTree(initialInsight);
            
            JsonNode symptomsNode = rootNode.path("symptoms");
            if (!symptomsNode.isMissingNode()) {
                result.symptoms = symptomsNode.asText();
            }
            
            JsonNode classesNode = rootNode.path("relevantClasses");
            if (classesNode.isArray()) {
                for (JsonNode classNode : classesNode) {
                    String className = classNode.asText();
                    if (className != null && !className.trim().isEmpty()) {
                        result.relevantClasses.add(className);
                    }
                }
            }
            
            JsonNode queriesNode = rootNode.path("queries");
            if (queriesNode.isArray()) {
                for (JsonNode queryNode : queriesNode) {
                    String query = queryNode.asText();
                    if (query != null && !query.trim().isEmpty()) {
                        result.queries.add(query);
                    }
                }
            }
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析初始洞察失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 收集所有信息
     */
    private void collectAllInformation(AnalysisResult analysis, String testCode, 
                                     String testOutput, String apiInfoWithSource) {
        // 1. 收集源码信息
        if (sourceTool != null) {
            collectWithTool(sourceTool, buildSourceCodePrompt(analysis, testCode), "SOURCE");
        }
        
        // 2. 收集JavaDoc信息
        if (javadocTool != null) {
            collectWithTool(javadocTool, buildJavaDocPrompt(analysis, testCode), "JAVADOC");
        }
        
        // 3. 添加API信息
        if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
            addApiInfo(apiInfoWithSource);
        }
        
        // 4. 简单的Web搜索（如果有空间的话）
        if (currentSize < MAX_TOTAL_SIZE * 0.8) { // 预留20%空间
            collectWebInfo(analysis, testOutput);
        }
    }
    
    /**
     * 使用工具收集信息
     */
    private void collectWithTool(Tool<String> tool, String prompt, String prefix) {
        try {
            List<ToolCall> toolCalls = llm.funcCall(prompt, Arrays.asList(tool));
            
            if (toolCalls != null) {
                for (ToolCall toolCall : toolCalls) {
            if (currentSize >= MAX_TOTAL_SIZE) break;
            
                    ToolResponse<String> response = tool.execute(toolCall.arguments);
            if (response.isSuccess()) {
                        addCollectedInfo(response.getResult(), prefix, toolCall);
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "工具调用失败 (" + prefix + "): " + e.getMessage());
        }
    }
    
    /**
     * 添加收集到的信息
     */
    private void addCollectedInfo(String content, String prefix, ToolCall toolCall) {
        if (content == null || content.isEmpty()) return;
        
        // 截断过长内容
        if (content.length() > 5000) {
            content = content.substring(0, 5000) + "...(内容已截断)";
        }
        
        if (currentSize + content.length() <= MAX_TOTAL_SIZE) {
            String searchType = (String) toolCall.arguments.get("search_type");
            String id = prefix + "_" + searchType + "_" + System.currentTimeMillis();
            String title = generateTitle(searchType, toolCall.arguments);
            
            CollectedInfo info = new CollectedInfo(id, title, content, 
                prefix.equals("SOURCE") ? InfoType.SOURCE_CODE : InfoType.JAVADOC, 0.8);
            
            collectedInfos.add(info);
            currentSize += content.length();
        }
        }
        
    /**
     * 添加API信息
     */
    private void addApiInfo(String apiInfoWithSource) {
        String content = apiInfoWithSource.length() > 8000 ? 
            apiInfoWithSource.substring(0, 8000) + "...(API信息已截断)" : apiInfoWithSource;
        
        if (currentSize + content.length() <= MAX_TOTAL_SIZE) {
            CollectedInfo info = new CollectedInfo("API_INFO", "API信息和源码", content, InfoType.SOURCE_CODE, 0.9);
            collectedInfos.add(info);
            currentSize += content.length();
        }
    }
    
    /**
     * 收集Web信息（异步版）- 并行获取完整网页内容
     */
    private void collectWebInfo(AnalysisResult analysis, String testOutput) {
        if (analysis.symptoms == null || analysis.symptoms.isEmpty()) return;
        
        try {
            String query = analysis.symptoms + " Java bug";
            ToolResponse<List<SearchResult>> response = webSearchTool.executeForResults(Map.of(
                "query", query,
                "max_results", 3
            ));
            
            if (response.isSuccess()) {
                List<SearchResult> results = response.getResult();
                
                // 创建线程池执行异步任务
                ExecutorService executor = Executors.newFixedThreadPool(Math.min(results.size(), 3));
                List<CompletableFuture<CollectedInfo>> futures = new ArrayList<>();
                
                try {
                    // 为每个搜索结果创建异步任务
                    for (SearchResult result : results) {
                        CompletableFuture<CollectedInfo> future = CompletableFuture.supplyAsync(() -> {
                            return extractSingleWebContent(result);
                        }, executor);
                        
                        futures.add(future);
                    }
                    
                    // 等待所有任务完成，设置30秒超时
                    CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])
                    );
                    
                    try {
                        allTasks.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LoggerUtil.logExec(Level.WARNING, "网页内容提取超时，使用已完成的结果");
                        // 不取消任务，让已完成的继续
                    }
                    
                    // 收集已完成的结果
                    for (CompletableFuture<CollectedInfo> future : futures) {
                        if (currentSize >= MAX_TOTAL_SIZE) break;
                        
                        try {
                            if (future.isDone() && !future.isCompletedExceptionally()) {
                                CollectedInfo info = future.get(1, TimeUnit.SECONDS); // 很短的超时，因为已经完成
                                if (info != null && currentSize + info.content.length() <= MAX_TOTAL_SIZE) {
                                    collectedInfos.add(info);
                                    currentSize += info.content.length();
                                    LoggerUtil.logExec(Level.INFO, "异步添加网页内容: " + info.source + 
                                        " (长度: " + info.content.length() + ")");
                                }
                            }
                        } catch (Exception e) {
                            LoggerUtil.logExec(Level.WARNING, "获取异步结果失败: " + e.getMessage());
                        }
                    }
                    
                } finally {
                    // 关闭线程池
                    executor.shutdown();
                    try {
                        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                            executor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "异步Web搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 提取单个网页内容（用于异步调用）
     */
    private CollectedInfo extractSingleWebContent(SearchResult result) {
        WebContentExtractor contentExtractor = null;
        try {
            LoggerUtil.logExec(Level.INFO, "异步提取网页内容: " + result.getUrl());
            
            contentExtractor = new WebContentExtractor(true);
            ToolResponse<String> contentResponse = contentExtractor.execute(result.getUrl());
            
            StringBuilder webContent = new StringBuilder();
            webContent.append("## ").append(result.getName()).append("\n\n");
            webContent.append("**来源**: ").append(result.getUrl()).append("\n\n");
            
            if (contentResponse.isSuccess()) {
                String fullContent = contentResponse.getResult();
                
                if (fullContent != null && !fullContent.trim().isEmpty()) {
                    // 限制单个网页的内容长度
                    if (fullContent.length() > 8000) {
                        fullContent = fullContent.substring(0, 8000) + "...(网页内容已截断)";
                    }
                    webContent.append(fullContent);
                    
                    String infoId = "WEB_" + result.getUrl().hashCode();
                    return new CollectedInfo(infoId, "网页: " + result.getName(), 
                        webContent.toString(), InfoType.WEB_SEARCH, 0.7);
                }
            }
            
            // 内容提取失败，使用摘要作为备选
            LoggerUtil.logExec(Level.INFO, "网页内容提取失败，使用摘要: " + result.getUrl());
            StringBuilder fallbackContent = new StringBuilder();
            fallbackContent.append("**标题**: ").append(result.getName()).append("\n");
            fallbackContent.append("**来源**: ").append(result.getUrl()).append("\n");
            fallbackContent.append("**摘要**: ").append(result.getSnippet()).append("\n");
            
            String infoId = "WEB_FALLBACK_" + result.getUrl().hashCode();
            return new CollectedInfo(infoId, "网页摘要: " + result.getName(), 
                fallbackContent.toString(), InfoType.WEB_SEARCH, 0.5);
                
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "单个网页内容提取异常: " + result.getUrl() + " - " + e.getMessage());
            
            // 发生异常时，返回基本摘要信息
            StringBuilder errorContent = new StringBuilder();
            errorContent.append("**标题**: ").append(result.getName()).append("\n");
            errorContent.append("**来源**: ").append(result.getUrl()).append("\n");
            errorContent.append("**摘要**: ").append(result.getSnippet()).append("\n");
            errorContent.append("**注意**: 完整内容提取失败\n");
            
            String infoId = "WEB_ERROR_" + result.getUrl().hashCode();
            return new CollectedInfo(infoId, "网页摘要: " + result.getName(), 
                errorContent.toString(), InfoType.WEB_SEARCH, 0.3);
                
        } finally {
            // 确保WebContentExtractor被正确关闭
            if (contentExtractor != null) {
                try {
                    contentExtractor.close();
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "关闭WebContentExtractor失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 判断信息是否足够 - 使用确定性工具选择
     */
    private boolean isInformationSufficient(AnalysisResult analysis, String testCode, String testOutput, int iteration) {
        // 第一次迭代总是不够，给机会优化
        if (iteration == 1 && collectedInfos.size() < 3) {
            return false;
        }
        
        try {
            String observePrompt = buildObservePrompt(analysis, testCode, testOutput);
            List<Tool<?>> decisionTools = Arrays.asList(INFO_SUFFICIENT, INFO_INSUFFICIENT);
            List<ToolCall> toolCalls = llm.funcCall(observePrompt, decisionTools);
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                String decision = toolCalls.get(0).toolName;
                LoggerUtil.logExec(Level.INFO, "信息充分性判断: " + decision);
                return INFO_SUFFICIENT.getName().equals(decision);
            } else {
                LoggerUtil.logExec(Level.WARNING, "LLM未返回决策，默认为信息不足");
                return false;
            }
                   
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "信息充分性判断失败: " + e.getMessage());
            // 如果判断失败，根据迭代次数决定
            return iteration >= 2;
        }
    }
    
    /**
     * 重新分析和优化
     */
    private AnalysisResult refineAnalysis(AnalysisResult currentAnalysis, String testCode, String testOutput) {
        try {
            String refinePrompt = buildRefinePrompt(currentAnalysis, testCode, testOutput);
            String response = llm.messageCompletion(refinePrompt, 0.7, false);
            
            // 解析优化后的分析结果
            AnalysisResult refined = parseInitialInsight(response);
            
            // 如果解析失败，保留原分析并添加一些补充查询
            if (refined.relevantClasses.isEmpty() && refined.queries.isEmpty()) {
                refined = currentAnalysis;
                refined.queries.add("exception handling");
                refined.queries.add("implementation details");
            }
            
            return refined;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "重新分析失败: " + e.getMessage());
            return currentAnalysis;
        }
    }
    
    /**
     * 构建源码收集prompt
     */
    private String buildSourceCodePrompt(AnalysisResult analysis, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("分析以下测试代码，决定需要搜索哪些源码：\n\n");
        prompt.append("症状: ").append(analysis.symptoms).append("\n");
        prompt.append("相关类: ").append(String.join(", ", analysis.relevantClasses)).append("\n\n");
        prompt.append("测试代码:\n```java\n").append(testCode).append("\n```\n\n");
        prompt.append("请选择最重要的源码搜索策略，优先搜索最相关的类和方法。");
        return prompt.toString();
    }
    
    /**
     * 构建JavaDoc收集prompt
     */
    private String buildJavaDocPrompt(AnalysisResult analysis, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("分析以下测试代码，决定需要搜索哪些API文档：\n\n");
        prompt.append("症状: ").append(analysis.symptoms).append("\n");
        prompt.append("相关类: ").append(String.join(", ", analysis.relevantClasses)).append("\n\n");
        prompt.append("测试代码:\n```java\n").append(testCode).append("\n```\n\n");
        prompt.append("请选择最重要的API文档搜索策略。");
        return prompt.toString();
    }
    
    /**
     * 构建观察prompt
     */
    private String buildObservePrompt(AnalysisResult analysis, String testCode, String testOutput) {
        try {
            // 构建收集信息摘要
            StringBuilder collectedInfosStr = new StringBuilder();
            if (collectedInfos.isEmpty()) {
                collectedInfosStr.append("尚未收集到信息\n");
            } else {
                for (int i = 0; i < Math.min(5, collectedInfos.size()); i++) {
                    CollectedInfo info = collectedInfos.get(i);
                    collectedInfosStr.append("- ").append(info.source).append(" (").append(info.type).append(")\n");
            }
        }
        
            return PromptGen.generateBugVerifyObservePrompt(testCode, testOutput, 
                collectedInfosStr.toString(), analysis.symptoms);
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.WARNING, "生成observe prompt失败: " + e.getMessage());
            return "根据收集的信息，判断是否足够分析测试失败原因。如果足够请回复'足够'，否则回复'不足够'。";
        }
    }
    
    /**
     * 构建重新分析prompt
     */
    private String buildRefinePrompt(AnalysisResult analysis, String testCode, String testOutput) {
        try {
            // 构建当前收集信息摘要
            StringBuilder currentInfosStr = new StringBuilder();
            for (CollectedInfo info : collectedInfos) {
                currentInfosStr.append("- ").append(info.source).append("\n");
        }
        
            return PromptGen.generateBugVerifyRefineAnalysisPrompt(testCode, testOutput,
                analysis.symptoms, String.join(", ", analysis.relevantClasses),
                String.join(", ", analysis.queries), currentInfosStr.toString());
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.WARNING, "生成refine prompt失败: " + e.getMessage());
            return "重新分析以下测试失败，提供更详细的症状描述和相关类：\n\n" + testCode;
        }
    }
    
    /**
     * 生成标题
     */
    private String generateTitle(String searchType, Map<String, Object> args) {
        switch (searchType) {
            case "by_class":
                return "类: " + args.get("class_name");
            case "by_method":
                return "方法: " + args.get("class_name") + "." + args.get("method_name");
            case "by_keyword":
                return "关键词: " + args.get("keyword");
            case "by_package":
                return "包: " + args.get("package_name");
            default:
                return "搜索: " + searchType;
        }
    }
    
    public void close() {
        // 清理资源
    }
    
    // 数据结构
    private static class AnalysisResult {
        String symptoms = "";
        List<String> relevantClasses = new ArrayList<>();
        List<String> queries = new ArrayList<>();
    }
    
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
            return String.format("[%s] %s (相关性: %.2f)", type, source, relevanceScore);
        }
    }
    
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