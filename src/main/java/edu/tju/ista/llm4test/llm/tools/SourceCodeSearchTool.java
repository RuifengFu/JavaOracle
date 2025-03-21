package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JDK源码检索工具
 */
public class SourceCodeSearchTool implements Tool<String> {
    private final String sourceRootPath;
    private final OpenAI llm;
    
    /**
     * 创建源码检索工具
     * @param sourceRootPath JDK源码的根目录路径
     */
    public SourceCodeSearchTool(String sourceRootPath) {
        this.sourceRootPath = sourceRootPath;
        this.llm = OpenAI.R1;
    }
    
    @Override
    public String getName() {
        return "source_code_search";
    }
    
    @Override
    public String getDescription() {
        return "根据提供的关键词（类名、方法名等）在JDK源码中检索相关代码";
    }
    
    @Override
    public ToolResponse<String> execute(String input) {
        try {
            // 使用LLM分析输入，找出可能的源文件路径或关键词
            String searchStrategy = analyzeSearchStrategy(input);
            List<Path> foundFiles = new ArrayList<>();
            
            // 检查分析结果是否包含具体路径
            if (searchStrategy.contains("/") || searchStrategy.contains("\\")) {
                // 直接按路径查找
                String[] paths = searchStrategy.split("\n");
                for (String path : paths) {
                    path = path.trim();
                    if (path.isEmpty()) continue;
                    
                    Path filePath = Paths.get(sourceRootPath, path);
                    if (Files.exists(filePath)) {
                        foundFiles.add(filePath);
                    }
                }
            } else {
                // 使用文本搜索
                String[] keywords = searchStrategy.split(",");
                for (String keyword : keywords) {
                    keyword = keyword.trim();
                    if (keyword.isEmpty()) continue;
                    
                    // 找出包含关键词的文件
                    List<Path> keywordMatches = findFilesByKeyword(keyword);
                    foundFiles.addAll(keywordMatches);
                    
                    // 限制文件数量
                    if (foundFiles.size() >= 5) break;
                }
            }
            
            if (foundFiles.isEmpty()) {
                return ToolResponse.failure("未找到与'" + input + "'相关的源代码文件");
            }
            
            // 读取源码内容
            StringBuilder sourceContent = new StringBuilder();
            for (Path file : foundFiles) {
                try {
                    String content = Files.readString(file);
                    sourceContent.append("=== ").append(file.getFileName()).append(" ===\n");
                    sourceContent.append(content).append("\n\n");
                    
                    // 限制返回的源码量
                    if (sourceContent.length() > 30000) {
                        sourceContent.append("...源码过多，已截断");
                        break;
                    }
                } catch (IOException e) {
                    LoggerUtil.logExec(Level.WARNING, "读取源码文件时出错: " + file + " - " + e.getMessage());
                }
            }
            
            return ToolResponse.success(sourceContent.toString());
        } catch (Exception e) {
            return ToolResponse.failure("源码搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 使用LLM分析输入，确定搜索策略
     */
    private String analyzeSearchStrategy(String input) {
        String prompt = String.format(
                "你是JDK源码检索助手。根据用户查询，请分析出最可能包含相关源码的文件路径或搜索关键词。\n" +
                "- 如果用户提供了具体类名，返回可能的源文件路径，如java/lang/String.java\n" +
                "- 如果用户只提供了模糊描述，返回几个关键搜索词，用逗号分隔\n" +
                "- 请只返回路径或关键词，不要解释\n\n" +
                "用户查询: %s\n" +
                "检索策略:", input);
        
        return llm.messageCompletion(prompt, 0.0);
    }
    
    /**
     * 根据关键词在源码库中查找相关文件
     */
    private List<Path> findFilesByKeyword(String keyword) throws IOException {
        List<Path> results = new ArrayList<>();
        
        // 查找文件名包含关键词的文件（效率更高）
        try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
            List<Path> fileNameMatches = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                    .limit(3) // 限制匹配数量
                    .collect(Collectors.toList());
            
            results.addAll(fileNameMatches);
        }
        
        // 如果文件名匹配不足，则搜索文件内容
        if (results.size() < 3) {
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
                int remainingLimit = 3 - results.size();
                List<Path> contentMatches = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !results.contains(p)) // 排除已匹配的文件
                        .filter(p -> {
                            try {
                                return Files.readString(p).toLowerCase().contains(keyword.toLowerCase());
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .limit(remainingLimit)
                        .collect(Collectors.toList());
                
                results.addAll(contentMatches);
            }
        }
        
        return results;
    }
    
    // 辅助类
    private static class ArrayList<T> extends java.util.ArrayList<T> {
        // 添加一个用于防止重复的辅助方法
        @Override
        public boolean add(T element) {
            if (!this.contains(element)) {
                return super.add(element);
            }
            return false;
        }
    }
} 