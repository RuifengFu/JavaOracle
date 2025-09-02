package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.stream.Collectors;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;

/**
 * 内容处理器 - 对网页内容进行智能分片、总结和相关性过滤
 */
@Deprecated
public class ContentProcessor implements Tool<String> {
    private final OpenAI llm;
    private final String cacheDir;
    private final ObjectMapper objectMapper;
    
    // 分片配置
    private static final int MAX_CHUNK_SIZE = 2000; // 最大分片大小
    private static final int MIN_CHUNK_SIZE = 200;  // 最小分片大小
    private static final double OVERLAP_RATIO = 0.1; // 重叠比例
    
    public ContentProcessor(String cacheDir) {
        this.llm = OpenAI.FlashModel;
        this.cacheDir = cacheDir != null ? cacheDir : "content_cache";
        this.objectMapper = new ObjectMapper();
        
        // 创建缓存目录
        try {
            Files.createDirectories(Paths.get(this.cacheDir));
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "创建缓存目录失败: " + e.getMessage());
        }
    }
    
    @Override
    public String getName() {
        return "process_and_summarize_content";
    }

    @Override
    public String getDescription() {
        return "Processes raw content (e.g., from a web page) by splitting it into chunks, " +
               "filtering them for relevance against an analysis context, and summarizing the key information. " +
               "Returns a formatted string of the processed, relevant content.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("url", "content", "analysisContext");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "url", "The original source URL of the content.",
                "content", "The raw content (in Markdown format) to be processed.",
                "analysisContext", "The analysis context or query to determine relevance."
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "url", "string",
                "content", "string",
                "analysisContext", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("url") || !args.containsKey("content") || !args.containsKey("analysisContext")) {
            return ToolResponse.failure("参数错误，必须提供 url, content, 和 analysisContext");
        }
        String url = (String) args.get("url");
        String content = (String) args.get("content");
        String analysisContext = (String) args.get("analysisContext");

        List<ProcessedContentChunk> processedChunks = processContent(url, content, analysisContext);

        if (processedChunks.isEmpty()) {
            return ToolResponse.success("没有找到相关内容。");
        }

        String formattedResult = processedChunks.stream()
                .map(ProcessedContentChunk::getFormattedContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        return ToolResponse.success(formattedResult);
    }
    
    /**
     * 处理网页内容：分片、过滤相关性、总结
     * @param url 原始URL
     * @param content 网页内容（Markdown格式）
     * @param analysisContext 分析上下文（来自初始分析）
     * @return 处理后的内容片段列表
     */
    public List<ProcessedContentChunk> processContent(String url, String content, String analysisContext) {
        try {
            // 检查缓存
            String cacheKey = generateCacheKey(url, content, analysisContext);
            List<ProcessedContentChunk> cached = loadFromCache(cacheKey);
            if (cached != null) {
                LoggerUtil.logExec(Level.INFO, "从缓存加载内容: " + url);
                return cached;
            }
            
            // 1. 分片
            List<ContentChunk> chunks = splitContent(content, url);
            LoggerUtil.logExec(Level.INFO, "内容分片完成，共 " + chunks.size() + " 个片段");
            
            // 2. 相关性过滤
            List<ContentChunk> relevantChunks = filterRelevantChunks(chunks, analysisContext);
            LoggerUtil.logExec(Level.INFO, "相关性过滤完成，保留 " + relevantChunks.size() + " 个片段");
            
            // 3. 总结和处理
            List<ProcessedContentChunk> processedChunks = summarizeChunks(relevantChunks, analysisContext);
            LoggerUtil.logExec(Level.INFO, "内容总结完成，生成 " + processedChunks.size() + " 个处理后的片段");
            
            // 保存到缓存
            saveToCache(cacheKey, processedChunks);
            
            return processedChunks;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "内容处理失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 将长文档分成较小的片段
     */
    private List<ContentChunk> splitContent(String content, String sourceUrl) {
        List<ContentChunk> chunks = new ArrayList<>();
        
        // 按段落分割
        String[] paragraphs = content.split("\n\n+");
        
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;
            
            // 检查添加这个段落是否会超过最大大小
            if (currentChunk.length() + paragraph.length() > MAX_CHUNK_SIZE && 
                currentChunk.length() > MIN_CHUNK_SIZE) {
                
                // 创建当前块
                ContentChunk chunk = new ContentChunk(
                    sourceUrl + "#chunk" + chunkIndex,
                    currentChunk.toString().trim(),
                    sourceUrl,
                    chunkIndex
                );
                chunks.add(chunk);
                
                // 开始新块，保留一些重叠
                String overlapText = getOverlapText(currentChunk.toString());
                currentChunk = new StringBuilder(overlapText);
                chunkIndex++;
            }
            
            currentChunk.append(paragraph).append("\n\n");
        }
        
        // 添加最后一个块
        if (currentChunk.length() > MIN_CHUNK_SIZE) {
            ContentChunk chunk = new ContentChunk(
                sourceUrl + "#chunk" + chunkIndex,
                currentChunk.toString().trim(),
                sourceUrl,
                chunkIndex
            );
            chunks.add(chunk);
        }
        
        return chunks;
    }
    
    /**
     * 获取重叠文本（用于分片之间的连接）
     */
    private String getOverlapText(String text) {
        if (text.length() < MAX_CHUNK_SIZE * OVERLAP_RATIO) {
            return "";
        }
        
        int overlapSize = (int) (MAX_CHUNK_SIZE * OVERLAP_RATIO);
        String overlap = text.substring(Math.max(0, text.length() - overlapSize));
        
        // 尝试在句号处断开
        int lastPeriod = overlap.lastIndexOf('.');
        if (lastPeriod > overlapSize / 2) {
            return overlap.substring(lastPeriod + 1).trim();
        }
        
        return overlap;
    }
    
    /**
     * 过滤与分析上下文相关的内容片段
     */
    private List<ContentChunk> filterRelevantChunks(List<ContentChunk> chunks, String analysisContext) {
        List<ContentChunk> relevantChunks = new ArrayList<>();
        
        // 批量处理以提高效率
        int batchSize = 5;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, chunks.size());
            List<ContentChunk> batch = chunks.subList(i, endIndex);
            
            List<ContentChunk> relevantInBatch = filterBatchRelevance(batch, analysisContext);
            relevantChunks.addAll(relevantInBatch);
        }
        
        return relevantChunks;
    }
    
    /**
     * 批量过滤相关性
     */
    private List<ContentChunk> filterBatchRelevance(List<ContentChunk> batch, String analysisContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个内容相关性分析专家。请分析以下内容片段与给定分析上下文的相关性。\n\n");
        prompt.append("分析上下文:\n").append(analysisContext).append("\n\n");
        prompt.append("请为每个内容片段评估相关性（0-10分，10分最相关）:\n\n");
        
        for (int i = 0; i < batch.size(); i++) {
            ContentChunk chunk = batch.get(i);
            prompt.append("=== 片段 ").append(i + 1).append(" ===\n");
            // 限制内容长度以避免提示过长
            String chunkPreview = chunk.getContent().length() > 1000 
                ? chunk.getContent().substring(0, 1000) + "..."
                : chunk.getContent();
            prompt.append(chunkPreview).append("\n\n");
        }
        
        prompt.append("请以JSON格式返回每个片段的相关性分数和简短理由:\n");
        prompt.append("{\n");
        prompt.append("  \"relevance_scores\": [\n");
        prompt.append("    {\"chunk\": 1, \"score\": 8, \"reason\": \"包含相关的API文档信息\"},\n");
        prompt.append("    {\"chunk\": 2, \"score\": 3, \"reason\": \"主要是无关的广告内容\"}\n");
        prompt.append("  ]\n");
        prompt.append("}");
        
        try {
            String response = llm.messageCompletion(prompt.toString());
            List<ContentChunk> relevantChunks = parseRelevanceResponse(response, batch);
            return relevantChunks;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "相关性评估失败: " + e.getMessage());
            // 如果失败，返回所有片段
            return batch;
        }
    }
    
    /**
     * 解析相关性评估响应
     */
    private List<ContentChunk> parseRelevanceResponse(String response, List<ContentChunk> batch) {
        List<ContentChunk> relevantChunks = new ArrayList<>();
        
        try {
            // 清理响应，提取JSON部分
            String cleanedResponse = extractJsonFromResponse(response);
            JsonNode root = objectMapper.readTree(cleanedResponse);
            JsonNode scores = root.get("relevance_scores");
            
            if (scores != null && scores.isArray()) {
                for (JsonNode scoreNode : scores) {
                    int chunkIndex = scoreNode.get("chunk").asInt() - 1; // 转换为0基索引
                    double score = scoreNode.get("score").asDouble();
                    
                    // 只保留相关性分数较高的片段
                    if (score >= 6.0 && chunkIndex >= 0 && chunkIndex < batch.size()) {
                        ContentChunk chunk = batch.get(chunkIndex);
                        chunk.setRelevanceScore(score);
                        chunk.setRelevanceReason(scoreNode.get("reason").asText());
                        relevantChunks.add(chunk);
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析相关性响应失败: " + e.getMessage());
            // 如果解析失败，返回所有片段
            return batch;
        }
        
        return relevantChunks;
    }
    
    /**
     * 对相关内容片段进行总结和处理
     */
    private List<ProcessedContentChunk> summarizeChunks(List<ContentChunk> chunks, String analysisContext) {
        List<ProcessedContentChunk> processedChunks = new ArrayList<>();
        
        for (ContentChunk chunk : chunks) {
            try {
                ProcessedContentChunk processed = summarizeChunk(chunk, analysisContext);
                if (processed != null) {
                    processedChunks.add(processed);
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "总结片段失败: " + e.getMessage());
                // 如果总结失败，使用原始内容
                ProcessedContentChunk fallback = new ProcessedContentChunk(
                    chunk.getId(),
                    chunk.getContent(),
                    chunk.getContent(), // 使用原始内容作为总结
                    Arrays.asList("原始内容"),
                    chunk.getRelevanceScore(),
                    chunk.getSourceUrl(),
                    chunk.getIndex()
                );
                processedChunks.add(fallback);
            }
        }
        
        return processedChunks;
    }
    
    /**
     * 总结单个内容片段
     */
    private ProcessedContentChunk summarizeChunk(ContentChunk chunk, String analysisContext) {
        String prompt = """
                你是一个技术文档总结专家。请分析以下内容片段，并根据给定的分析上下文进行总结。
                
                分析上下文:
                %s
                
                内容片段:
                %s
                
                请提供:
                1. 简洁的总结（保留技术细节和关键信息）
                2. 与分析上下文相关的关键点列表
                3. 原始表达方式（避免歧义）
                
                输出格式:
                {
                  "summary": "简洁总结，保留技术细节",
                  "key_points": ["关键点1", "关键点2"],
                  "preserved_content": "保留原始表达的重要部分"
                }
                """.formatted(analysisContext, chunk.getContent());
        
        String response = llm.messageCompletion(prompt);
        
        try {
            String cleanedResponse = extractJsonFromResponse(response);
            JsonNode root = objectMapper.readTree(cleanedResponse);
            
            String summary = root.get("summary").asText();
            List<String> keyPoints = new ArrayList<>();
            JsonNode keyPointsNode = root.get("key_points");
            if (keyPointsNode != null && keyPointsNode.isArray()) {
                for (JsonNode point : keyPointsNode) {
                    keyPoints.add(point.asText());
                }
            }
            String preservedContent = root.get("preserved_content").asText();
            
            return new ProcessedContentChunk(
                chunk.getId(),
                chunk.getContent(),
                summary,
                keyPoints,
                chunk.getRelevanceScore(),
                chunk.getSourceUrl(),
                chunk.getIndex(),
                preservedContent
            );
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析总结响应失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 从响应中提取JSON内容
     */
    private String extractJsonFromResponse(String response) {
        // 移除markdown代码块标记
        response = response.replaceAll("```json\\n", "").replaceAll("```", "");
        
        // 查找JSON对象的开始和结束
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}') + 1;
        
        if (start >= 0 && end > start) {
            return response.substring(start, end);
        }
        
        return response;
    }
    
    /**
     * 生成缓存键
     */
    private String generateCacheKey(String url, String content, String analysisContext) {
        try {
            String combined = url + "|" + content.hashCode() + "|" + analysisContext.hashCode();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(combined.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf((url + content + analysisContext).hashCode());
        }
    }
    
    /**
     * 从缓存加载
     */
    private List<ProcessedContentChunk> loadFromCache(String cacheKey) {
        try {
            Path cacheFile = Paths.get(cacheDir, cacheKey + ".json");
            if (Files.exists(cacheFile)) {
                String content = Files.readString(cacheFile);
                JsonNode root = objectMapper.readTree(content);
                
                List<ProcessedContentChunk> chunks = new ArrayList<>();
                for (JsonNode node : root) {
                    ProcessedContentChunk chunk = objectMapper.treeToValue(node, ProcessedContentChunk.class);
                    chunks.add(chunk);
                }
                return chunks;
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.FINE, "加载缓存失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 保存到缓存
     */
    private void saveToCache(String cacheKey, List<ProcessedContentChunk> chunks) {
        try {
            Path cacheFile = Paths.get(cacheDir, cacheKey + ".json");
            String content = objectMapper.writeValueAsString(chunks);
            Files.writeString(cacheFile, content);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.FINE, "保存缓存失败: " + e.getMessage());
        }
    }
    
    /**
     * 内容片段类
     */
    public static class ContentChunk {
        private String id;
        private String content;
        private String sourceUrl;
        private int index;
        private double relevanceScore;
        private String relevanceReason;
        
        public ContentChunk(String id, String content, String sourceUrl, int index) {
            this.id = id;
            this.content = content;
            this.sourceUrl = sourceUrl;
            this.index = index;
            this.relevanceScore = 0.0;
        }
        
        // Getters and setters
        public String getId() { return id; }
        public String getContent() { return content; }
        public String getSourceUrl() { return sourceUrl; }
        public int getIndex() { return index; }
        public double getRelevanceScore() { return relevanceScore; }
        public String getRelevanceReason() { return relevanceReason; }
        
        public void setRelevanceScore(double score) { this.relevanceScore = score; }
        public void setRelevanceReason(String reason) { this.relevanceReason = reason; }
    }
    
    /**
     * 处理后的内容片段类
     */
    public static class ProcessedContentChunk {
        private String id;
        private String originalContent;
        private String summary;
        private List<String> keyPoints;
        private double relevanceScore;
        private String sourceUrl;
        private int index;
        private String preservedContent;
        
        // 默认构造函数（用于JSON反序列化）
        public ProcessedContentChunk() {}
        
        public ProcessedContentChunk(String id, String originalContent, String summary, 
                                   List<String> keyPoints, double relevanceScore, 
                                   String sourceUrl, int index) {
            this(id, originalContent, summary, keyPoints, relevanceScore, sourceUrl, index, "");
        }
        
        public ProcessedContentChunk(String id, String originalContent, String summary, 
                                   List<String> keyPoints, double relevanceScore, 
                                   String sourceUrl, int index, String preservedContent) {
            this.id = id;
            this.originalContent = originalContent;
            this.summary = summary;
            this.keyPoints = keyPoints != null ? keyPoints : new ArrayList<>();
            this.relevanceScore = relevanceScore;
            this.sourceUrl = sourceUrl;
            this.index = index;
            this.preservedContent = preservedContent;
        }
        
        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        
        public List<String> getKeyPoints() { return keyPoints; }
        public void setKeyPoints(List<String> keyPoints) { this.keyPoints = keyPoints; }
        
        public double getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }
        
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        
        public String getPreservedContent() { return preservedContent; }
        public void setPreservedContent(String preservedContent) { this.preservedContent = preservedContent; }
        
        /**
         * 获取格式化的内容用于展示
         */
        public String getFormattedContent() {
            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(sourceUrl).append(" (相关性: ").append(String.format("%.1f", relevanceScore)).append(")\n\n");
            sb.append("### 总结\n").append(summary).append("\n\n");
            
            if (!keyPoints.isEmpty()) {
                sb.append("### 关键点\n");
                for (String point : keyPoints) {
                    sb.append("- ").append(point).append("\n");
                }
                sb.append("\n");
            }
            
            if (preservedContent != null && !preservedContent.isEmpty()) {
                sb.append("### 原始内容\n").append(preservedContent).append("\n\n");
            }
            
            return sb.toString();
        }
    }
} 