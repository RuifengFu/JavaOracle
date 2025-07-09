package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 简化的源码搜索工具 - 只提供基础静态搜索功能
 * Agent负责智能决策，工具只负责执行具体的搜索操作
 */
public class SimplifiedSourceCodeSearchTool implements Tool<String> {
    private final String sourceRootPath;
    private final Map<String, Path> classPathCache = new HashMap<>();
    
    public SimplifiedSourceCodeSearchTool(String sourceRootPath) {
        this.sourceRootPath = sourceRootPath;
    }
    
    @Override
    public String getName() {
        return "simplified_source_search";
    }
    
    @Override
    public String getDescription() {
        return "静态源码搜索工具 - 在JDK源码中查找类、方法和代码片段。支持精确类名搜索、方法实现查找、关键词匹配和相关类发现。";
    }
    
    @Override
    public List<String> getParameters() {
        return List.of("search_type", "class_name", "method_name", "keyword", "file_path");
    }
    
    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
            "search_type", "搜索类型 (必填):\n" +
                          "• 'by_class' - 按完整类名查找源码文件\n" +
                          "• 'by_method' - 在指定类中查找方法实现\n" +
                          "• 'by_keyword' - 在所有源码中搜索关键词\n" +
                          "• 'by_path' - 直接获取指定路径的文件内容\n" +
                          "• 'related_classes' - 查找与指定类相关的其他类",
            
            "class_name", "类名 (用于by_class, by_method, related_classes):\n" +
                         "• 推荐使用完整类名，如 'java.util.HashMap'\n" +
                         "• 支持简单类名，如 'HashMap'\n" +
                         "• 支持JDK模块化路径解析",
            
            "method_name", "方法名 (用于by_method):\n" +
                          "• 方法的简单名称，如 'put', 'get', 'toString'\n" +
                          "• 会查找所有重载版本的方法\n" +
                          "• 返回方法的完整定义和实现代码",
            
            "keyword", "关键词 (用于by_keyword):\n" +
                      "• 可以是类名、方法名、异常名等\n" +
                      "• 支持大小写不敏感搜索\n" +
                      "• 返回包含该关键词的代码片段及上下文",
            
            "file_path", "文件路径 (用于by_path):\n" +
                        "• 相对于源码根目录的路径\n" +
                        "• 如 'java/util/HashMap.java'\n" +
                        "• 支持JDK模块结构路径"
        );
    }
    
    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
            "search_type", "string (enum: by_class|by_method|by_keyword|by_path|related_classes)",
            "class_name", "string (optional, required for by_class|by_method|related_classes)", 
            "method_name", "string (optional, required for by_method)",
            "keyword", "string (optional, required for by_keyword)",
            "file_path", "string (optional, required for by_path)"
        );
    }
    
    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        try {
            String searchType = (String) args.get("search_type");
            if (searchType == null) {
                return ToolResponse.failure("必须指定search_type参数");
            }
            
            switch (searchType) {
                case "by_class":
                    return searchByClassName((String) args.get("class_name"));
                case "by_method":
                    return searchByMethod((String) args.get("class_name"), (String) args.get("method_name"));
                case "by_keyword":
                    return searchByKeyword((String) args.get("keyword"));
                case "by_path":
                    return getFileContent((String) args.get("file_path"));
                case "related_classes":
                    return findRelatedClasses((String) args.get("class_name"));
                default:
                    return ToolResponse.failure("不支持的搜索类型: " + searchType);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "源码搜索失败: " + e.getMessage());
            return ToolResponse.failure("搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 按类名精确查找
     */
    public ToolResponse<String> searchByClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("类名不能为空");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("未找到类: " + className);
            }
            
            String content = Files.readString(classFile);
            String relativePath = getRelativePath(classFile);
            
            StringBuilder result = new StringBuilder();
            result.append("## 类文件: ").append(relativePath).append("\n\n");
            result.append("```java\n").append(content).append("\n```");
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("读取文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 按方法名在指定类中查找
     */
    public ToolResponse<String> searchByMethod(String className, String methodName) {
        if (className == null || methodName == null) {
            return ToolResponse.failure("类名和方法名都不能为空");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("未找到类: " + className);
            }
            
            String content = Files.readString(classFile);
            List<String> methodMatches = extractMethodDefinitions(content, methodName);
            
            if (methodMatches.isEmpty()) {
                return ToolResponse.failure("在类 " + className + " 中未找到方法: " + methodName);
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## 在类 ").append(className).append(" 中找到方法 ").append(methodName).append("\n\n");
            result.append("**文件路径**: ").append(getRelativePath(classFile)).append("\n\n");
            
            for (int i = 0; i < methodMatches.size(); i++) {
                result.append("### 方法定义 ").append(i + 1).append("\n\n");
                result.append("```java\n").append(methodMatches.get(i)).append("\n```\n\n");
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("搜索方法失败: " + e.getMessage());
        }
    }
    
    /**
     * 按关键词搜索文件内容
     */
    public ToolResponse<String> searchByKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ToolResponse.failure("关键词不能为空");
        }
        
        try {
            List<SearchResult> results = new ArrayList<>();
            
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".java"))
                     .forEach(path -> {
                         try {
                             String content = Files.readString(path);
                             if (content.toLowerCase().contains(keyword.toLowerCase())) {
                                 List<String> matchingLines = extractMatchingLines(content, keyword);
                                 if (!matchingLines.isEmpty()) {
                                     results.add(new SearchResult(getRelativePath(path), matchingLines));
                                 }
                             }
                         } catch (IOException e) {
                             // 忽略单个文件的读取错误
                         }
                     });
            }
            
            if (results.isEmpty()) {
                return ToolResponse.failure("未找到包含关键词 '" + keyword + "' 的文件");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## 关键词搜索结果: ").append(keyword).append("\n\n");
            result.append("找到 ").append(results.size()).append(" 个匹配的文件\n\n");
            
            for (SearchResult searchResult : results) {
                result.append("### ").append(searchResult.filePath).append("\n\n");
                for (String line : searchResult.matchingLines) {
                    result.append("```java\n").append(line).append("\n```\n\n");
                }
                if (result.length() > 15000) { // 增加输出长度限制
                    result.append("...结果过多，已截断 (共找到 ").append(results.size()).append(" 个文件)");
                    break;
                }
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("关键词搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定路径的文件内容
     */
    public ToolResponse<String> getFileContent(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return ToolResponse.failure("文件路径不能为空");
        }
        
        try {
            Path fullPath = Paths.get(sourceRootPath, filePath);
            if (!Files.exists(fullPath)) {
                return ToolResponse.failure("文件不存在: " + filePath);
            }
            
            if (!Files.isRegularFile(fullPath)) {
                return ToolResponse.failure("指定路径不是文件: " + filePath);
            }
            
            String content = Files.readString(fullPath);
            
            StringBuilder result = new StringBuilder();
            result.append("## 文件内容: ").append(filePath).append("\n\n");
            result.append("```java\n").append(content).append("\n```");
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("读取文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 查找与指定类相关的类（同包、继承关系等）
     */
    public ToolResponse<String> findRelatedClasses(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("类名不能为空");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("未找到类: " + className);
            }
            
            List<Path> relatedFiles = new ArrayList<>();
            
            // 1. 查找同包下的类
            Path parentDir = classFile.getParent();
            if (parentDir != null) {
                try (Stream<Path> packageFiles = Files.list(parentDir)) {
                    packageFiles.filter(Files::isRegularFile)
                               .filter(p -> p.toString().endsWith(".java"))
                               .filter(p -> !p.equals(classFile))
                               .limit(15) // 增加同包类的数量
                               .forEach(relatedFiles::add);
                }
            }
            
            // 2. 查找引用该类的文件
            String simpleClassName = className.contains(".") ? 
                className.substring(className.lastIndexOf('.') + 1) : className;
            
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !relatedFiles.contains(p) && !p.equals(classFile))
                     .limit(30) // 增加相关类的数量
                     .forEach(path -> {
                         try {
                             String content = Files.readString(path);
                             if (content.contains("import " + className + ";") ||
                                 content.contains("new " + simpleClassName + "(") ||
                                 content.contains(simpleClassName + ".") ||
                                 content.contains("extends " + simpleClassName) ||
                                 content.contains("implements " + simpleClassName)) {
                                 relatedFiles.add(path);
                             }
                         } catch (IOException e) {
                             // 忽略读取错误
                         }
                     });
            }
            
            if (relatedFiles.isEmpty()) {
                return ToolResponse.failure("未找到与类 " + className + " 相关的其他类");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## 与类 ").append(className).append(" 相关的类\n\n");
            result.append("找到 ").append(relatedFiles.size()).append(" 个相关文件:\n\n");
            
            for (Path relatedFile : relatedFiles) {
                result.append("- **").append(getRelativePath(relatedFile)).append("**\n");
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("查找相关类失败: " + e.getMessage());
        }
    }
    
    // ===================== 私有辅助方法 =====================
    
    /**
     * 精确查找类文件
     */
    private Path findExactClass(String className) {
        // 检查缓存
        if (classPathCache.containsKey(className)) {
            return classPathCache.get(className);
        }
        
        String simpleClassName = className.contains(".") ? 
            className.substring(className.lastIndexOf('.') + 1) : className;
        
        // 构建可能的路径
        List<String> possiblePaths = new ArrayList<>();
        
        // 1. 直接路径转换
        if (className.contains(".")) {
            possiblePaths.add(className.replace('.', '/') + ".java");
        }
        
        // 2. JDK模块结构路径
        if (className.startsWith("java.") || className.startsWith("javax.")) {
            String[] parts = className.split("\\.");
            if (parts.length >= 2) {
                String moduleName = parts[0] + "." + parts[1]; // 如 java.base, java.util
                String packagePath = String.join("/", Arrays.copyOfRange(parts, 0, parts.length - 1));
                
                // JDK模块结构: module/share/classes/package/Class.java
                possiblePaths.add(moduleName + "/share/classes/" + packagePath + "/" + simpleClassName + ".java");
                possiblePaths.add(moduleName + "/share/classes/" + className.replace('.', '/') + ".java");
                
                // 简化的模块结构
                possiblePaths.add(moduleName + "/" + packagePath + "/" + simpleClassName + ".java");
                possiblePaths.add(moduleName + "/" + className.replace('.', '/') + ".java");
            }
        }
        
        // 3. 常见包路径
        String[] commonPackages = {
            "java/lang/", "java/util/", "java/io/", "java/nio/",
            "java/net/", "java/security/", "java/text/", "java/time/",
            "java/util/concurrent/", "java/util/stream/", "java/util/function/"
        };
        
        for (String pkg : commonPackages) {
            possiblePaths.add(pkg + simpleClassName + ".java");
        }
        
        // 4. 尝试所有可能的路径
        for (String path : possiblePaths) {
            Path filePath = Paths.get(sourceRootPath, path);
            if (Files.exists(filePath)) {
                classPathCache.put(className, filePath);
                LoggerUtil.logExec(Level.INFO, "找到类文件: " + className + " -> " + path);
                return filePath;
            }
        }
        
        // 5. 遍历查找（作为最后手段）
        try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
            Optional<Path> result = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.getFileName().toString().equals(simpleClassName + ".java"))
                .findFirst();
            
            if (result.isPresent()) {
                classPathCache.put(className, result.get());
                LoggerUtil.logExec(Level.INFO, "通过遍历找到类文件: " + className + " -> " + getRelativePath(result.get()));
                return result.get();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "查找类文件时出错: " + e.getMessage());
        }
        
        LoggerUtil.logExec(Level.WARNING, "未找到类文件: " + className);
        return null;
    }
    
    /**
     * 提取方法定义
     */
    private List<String> extractMethodDefinitions(String content, String methodName) {
        List<String> methods = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            // 简单的方法定义匹配
            if (line.contains(" " + methodName + "(") && 
                (line.contains("public") || line.contains("private") || line.contains("protected"))) {
                
                StringBuilder methodDef = new StringBuilder();
                int braceCount = 0;
                boolean inMethod = false;
                
                // 提取完整的方法定义
                for (int j = i; j < lines.length && j < i + 50; j++) { // 限制提取长度
                    String currentLine = lines[j];
                    methodDef.append(currentLine).append("\n");
                    
                    // 计算大括号
                    for (char c : currentLine.toCharArray()) {
                        if (c == '{') {
                            braceCount++;
                            inMethod = true;
                        } else if (c == '}') {
                            braceCount--;
                        }
                    }
                    
                    // 方法结束
                    if (inMethod && braceCount == 0) {
                        break;
                    }
                }
                
                methods.add(methodDef.toString());
            }
        }
        
        return methods;
    }
    
    /**
     * 提取包含关键词的行
     */
    private List<String> extractMatchingLines(String content, String keyword) {
        List<String> matchingLines = new ArrayList<>();
        String[] lines = content.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(keyword.toLowerCase())) {
                // 添加上下文（前后各一行）
                StringBuilder context = new StringBuilder();
                
                if (i > 0) {
                    context.append(lines[i - 1]).append("\n");
                }
                context.append(">>> ").append(lines[i]).append(" <<<\n"); // 标记匹配行
                if (i < lines.length - 1) {
                    context.append(lines[i + 1]).append("\n");
                }
                
                matchingLines.add(context.toString());
                
                if (matchingLines.size() >= 5) { // 限制每个文件的匹配行数
                    break;
                }
            }
        }
        
        return matchingLines;
    }
    
    /**
     * 获取相对路径
     */
    private String getRelativePath(Path file) {
        return Paths.get(sourceRootPath).relativize(file).toString();
    }
    
    /**
     * 搜索结果内部类
     */
    private static class SearchResult {
        final String filePath;
        final List<String> matchingLines;
        
        SearchResult(String filePath, List<String> matchingLines) {
            this.filePath = filePath;
            this.matchingLines = matchingLines;
        }
    }
} 