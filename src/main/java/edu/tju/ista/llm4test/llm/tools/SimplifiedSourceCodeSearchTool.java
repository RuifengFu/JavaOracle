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
        return "Static source code search tool - Search for classes, methods, and code snippets in JDK source code. Supports exact class name search, method implementation lookup, keyword matching, and related class discovery.";
    }
    
    @Override
    public List<String> getParameters() {
        return List.of("search_type", "class_name", "method_name", "keyword", "file_path");
    }
    
    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
            "search_type", "Search type (required):\n" +
                          "• 'by_class' - Search for source file by full class name\n" +
                          "• 'by_method' - Search for method implementation in specified class\n" +
                          "• 'by_keyword' - Search for keyword in all source code\n" +
                          "• 'by_path' - Directly get content of specified path file\n" +
                          "• 'related_classes' - Search for classes related to specified class",
            
            "class_name", "Class name (for by_class, by_method, related_classes):\n" +
                         "• Recommend using full class name, e.g. 'java.util.HashMap'\n" +
                         "• Supports simple class name, e.g. 'HashMap'\n" +
                         "• Supports JDK modular path resolution",
            
            "method_name", "Method name (for by_method):\n" +
                          "• Simple method name, e.g. 'put', 'get', 'toString'\n" +
                          "• Will search for all overloaded versions\n" +
                          "• Returns complete method definition and implementation code",
            
            "keyword", "Keyword (for by_keyword):\n" +
                      "• Can be class name, method name, exception name, etc.\n" +
                      "• Supports case-insensitive search\n" +
                      "• Returns code snippets containing the keyword and context",
            
            "file_path", "File path (for by_path):\n" +
                        "• Path relative to source root\n" +
                        "• e.g. 'java/util/HashMap.java'\n" +
                        "• Supports JDK module structure paths"
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
                return ToolResponse.failure("Must specify search_type parameter");
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
                    return ToolResponse.failure("Unsupported search type: " + searchType);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Source code search failed: " + e.getMessage());
            return ToolResponse.failure("Search failed: " + e.getMessage());
        }
    }
    
    /**
     * 按类名精确查找
     */
    public ToolResponse<String> searchByClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("Class name cannot be empty");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("Class not found: " + className);
            }
            
            String content = Files.readString(classFile);
            String relativePath = getRelativePath(classFile);
            
            StringBuilder result = new StringBuilder();
            result.append("## Class file: ").append(relativePath).append("\n\n");
            result.append("```java\n").append(content).append("\n```");
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("Failed to read file: " + e.getMessage());
        }
    }
    
    /**
     * 按方法名在指定类中查找
     */
    public ToolResponse<String> searchByMethod(String className, String methodName) {
        if (className == null || methodName == null) {
            return ToolResponse.failure("Class name and method name cannot be empty");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("Class not found: " + className);
            }
            
            String content = Files.readString(classFile);
            List<String> methodMatches = extractMethodDefinitions(content, methodName);
            
            if (methodMatches.isEmpty()) {
                return ToolResponse.failure("Method " + methodName + " not found in class " + className);
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## Found method ").append(methodName).append(" in class ").append(className).append("\n\n");
            result.append("**File path**: ").append(getRelativePath(classFile)).append("\n\n");
            
            for (int i = 0; i < methodMatches.size(); i++) {
                result.append("### Method definition ").append(i + 1).append("\n\n");
                result.append("```java\n").append(methodMatches.get(i)).append("\n```\n\n");
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("Search method failed: " + e.getMessage());
        }
    }
    
    /**
     * 按关键词搜索文件内容
     */
    public ToolResponse<String> searchByKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ToolResponse.failure("Keyword cannot be empty");
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
                             // Ignore single file read errors
                         }
                     });
            }
            
            if (results.isEmpty()) {
                return ToolResponse.failure("No files found containing keyword '" + keyword + "'");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## Keyword search results: ").append(keyword).append("\n\n");
            result.append("Found ").append(results.size()).append(" matching files\n\n");
            
            for (SearchResult searchResult : results) {
                result.append("### ").append(searchResult.filePath).append("\n\n");
                for (String line : searchResult.matchingLines) {
                    result.append("```java\n").append(line).append("\n```\n\n");
                }
                if (result.length() > 15000) { // Increase output length limit
                    result.append("...too many results, truncated (found ").append(results.size()).append(" files)");
                    break;
                }
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("Keyword search failed: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定路径的文件内容
     */
    public ToolResponse<String> getFileContent(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return ToolResponse.failure("File path cannot be empty");
        }
        
        try {
            Path fullPath = Paths.get(sourceRootPath, filePath);
            if (!Files.exists(fullPath)) {
                return ToolResponse.failure("File does not exist: " + filePath);
            }
            
            if (!Files.isRegularFile(fullPath)) {
                return ToolResponse.failure("Specified path is not a file: " + filePath);
            }
            
            String content = Files.readString(fullPath);
            
            StringBuilder result = new StringBuilder();
            result.append("## File content: ").append(filePath).append("\n\n");
            result.append("```java\n").append(content).append("\n```");
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("Failed to read file: " + e.getMessage());
        }
    }
    
    /**
     * 查找与指定类相关的类（同包、继承关系等）
     */
    public ToolResponse<String> findRelatedClasses(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("Class name cannot be empty");
        }
        
        try {
            Path classFile = findExactClass(className);
            if (classFile == null) {
                return ToolResponse.failure("Class not found: " + className);
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
                             // Ignore read errors
                         }
                     });
            }
            
            if (relatedFiles.isEmpty()) {
                return ToolResponse.failure("No other classes related to class " + className + " found");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## Classes related to class ").append(className).append("\n\n");
            result.append("Found ").append(relatedFiles.size()).append(" related files:\n\n");
            
            for (Path relatedFile : relatedFiles) {
                result.append("- **").append(getRelativePath(relatedFile)).append("**\n");
            }
            
            return ToolResponse.success(result.toString());
        } catch (IOException e) {
            return ToolResponse.failure("Failed to find related classes: " + e.getMessage());
        }
    }
    
    // ===================== Private helper methods =====================
    
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
                LoggerUtil.logExec(Level.INFO, "Found class file: " + className + " -> " + path);
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
                LoggerUtil.logExec(Level.INFO, "Found class file by traversal: " + className + " -> " + getRelativePath(result.get()));
                return result.get();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "Error searching for class file: " + e.getMessage());
        }
        
        LoggerUtil.logExec(Level.WARNING, "Class file not found: " + className);
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