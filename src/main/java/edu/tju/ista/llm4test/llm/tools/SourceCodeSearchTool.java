package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JDK源码检索工具 - 增强版
 */
public class SourceCodeSearchTool implements Tool<String> {
    private final String sourceRootPath;
    private final OpenAI llm;
    private Map<String, Path> classPathCache = new HashMap<>();
    
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
            // 分析输入，确定搜索策略
            SearchContext context = analyzeSearchInput(input);
            List<Path> foundFiles = new ArrayList<>();
            
            // 首先尝试精确查找类文件
            if (context.getTargetClass() != null) {
                Path classFile = findExactClass(context.getTargetClass());
                if (classFile != null) {
                    foundFiles.add(classFile);
                    
                    // 如果指定了方法，查找该方法的实现和使用
                    if (context.getTargetMethod() != null) {
                        List<Path> methodImplementations = findMethodImplementations(
                                context.getTargetClass(), context.getTargetMethod());
                        foundFiles.addAll(methodImplementations);
                    }
                    
                    // 如果是Bug分析，查找相关的实现类和接口
                    if (context.isBugRelated()) {
                        List<Path> relatedClasses = findRelatedClasses(context.getTargetClass());
                        foundFiles.addAll(relatedClasses);
                    }
                }
            }
            
            // 如果没有找到精确的类或者需要更宽泛的搜索
            if (foundFiles.isEmpty() || context.isNeedBroadSearch()) {
                // 使用关键词搜索
                for (String keyword : context.getKeywords()) {
                    List<Path> keywordMatches = findFilesByKeyword(keyword, context);
                    // 避免添加重复
                    for (Path match : keywordMatches) {
                        if (!foundFiles.contains(match)) {
                            foundFiles.add(match);
                        }
                    }
                    
                    // 限制文件数量
                    if (foundFiles.size() >= 5 && !context.isBugRelated()) break;
                }
            }
            
            // 如果是Bug分析且找到错误消息，尝试查找包含错误消息的源码
            if (context.isBugRelated() && context.getErrorMessage() != null) {
                List<Path> errorRelatedFiles = findFilesWithErrorMessage(context.getErrorMessage());
                for (Path file : errorRelatedFiles) {
                    if (!foundFiles.contains(file)) {
                        foundFiles.add(file);
                    }
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
                    
                    // 添加文件头，包含完整路径信息
                    String relativePath = getRelativePath(file);
                    sourceContent.append("=== ").append(relativePath).append(" ===\n");
                    
                    // 如果有目标方法，高亮显示
                    if (context.getTargetMethod() != null) {
                        content = highlightMethod(content, context.getTargetMethod());
                    }
                    
                    // 如果有错误消息，高亮显示
                    if (context.getErrorMessage() != null) {
                        content = highlightText(content, context.getErrorMessage());
                    }
                    
                    sourceContent.append(content).append("\n\n");
                    
                    // 限制返回的源码量
                    if (sourceContent.length() > 50000) {
                        sourceContent.append("...源码过多，已截断");
                        break;
                    }
                } catch (IOException e) {
                    LoggerUtil.logExec(Level.WARNING, "读取源码文件时出错: " + file + " - " + e.getMessage());
                }
            }
            
            // 添加搜索摘要
            StringBuilder summary = new StringBuilder();
            summary.append("## 源码搜索摘要\n\n");
            summary.append("- **搜索输入**: ").append(input).append("\n");
            summary.append("- **目标类**: ").append(context.getTargetClass() != null ? context.getTargetClass() : "无").append("\n");
            if (context.getTargetMethod() != null) {
                summary.append("- **目标方法**: ").append(context.getTargetMethod()).append("\n");
            }
            summary.append("- **找到的文件**: ").append(foundFiles.size()).append("个\n");
            summary.append("- **文件列表**:\n");
            for (Path file : foundFiles) {
                summary.append("  - ").append(getRelativePath(file)).append("\n");
            }
            summary.append("\n---\n\n");
            
            // 将摘要放在返回内容的前面
            sourceContent.insert(0, summary.toString());
            
            return ToolResponse.success(sourceContent.toString());
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "源码搜索失败: " + e.getMessage());
            e.printStackTrace();
            return ToolResponse.failure("源码搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 分析输入，确定搜索上下文
     */
    private SearchContext analyzeSearchInput(String input) {
        String prompt = String.format(
                "你是Java源码分析专家。请从用户查询中提取关键信息，帮助精确定位源码。\n\n" +
                "用户查询: %s\n\n" +
                "请提供以下信息，JSON格式返回:\n" +
                "1. targetClass: 用户想查找的具体类名，如java.util.HashMap或HashMap，无则null\n" +
                "2. targetMethod: 用户想查找的方法，如put或size，无则null\n" +
                "3. keywords: 查找源码的关键词列表，至少3个\n" +
                "4. errorMessage: 可能的错误消息片段，无则null\n" +
                "5. bugRelated: 是否与bug分析相关，true或false\n" +
                "6. needBroadSearch: 是否需要宽泛搜索，true或false", input);
        
        String response = llm.messageCompletion(prompt, 0.0);
        
        // 解析LLM返回的JSON
        SearchContext context = new SearchContext();
        
        try {
            // 提取targetClass
            Pattern classPattern = Pattern.compile("\"targetClass\"\\s*:\\s*\"([^\"]+)\"");
            Matcher classMatcher = classPattern.matcher(response);
            if (classMatcher.find()) {
                String targetClass = classMatcher.group(1);
                if (!targetClass.equals("null")) {
                    context.setTargetClass(targetClass);
                }
            }
            
            // 提取targetMethod
            Pattern methodPattern = Pattern.compile("\"targetMethod\"\\s*:\\s*\"([^\"]+)\"");
            Matcher methodMatcher = methodPattern.matcher(response);
            if (methodMatcher.find()) {
                String targetMethod = methodMatcher.group(1);
                if (!targetMethod.equals("null")) {
                    context.setTargetMethod(targetMethod);
                }
            }
            
            // 提取keywords
            Pattern keywordsPattern = Pattern.compile("\"keywords\"\\s*:\\s*\\[([^\\]]+)\\]");
            Matcher keywordsMatcher = keywordsPattern.matcher(response);
            if (keywordsMatcher.find()) {
                String keywordsStr = keywordsMatcher.group(1);
                String[] keywordArray = keywordsStr.split(",");
                for (String keyword : keywordArray) {
                    keyword = keyword.trim().replaceAll("\"", "");
                    if (!keyword.isEmpty()) {
                        context.addKeyword(keyword);
                    }
                }
            }
            
            // 提取errorMessage
            Pattern errorPattern = Pattern.compile("\"errorMessage\"\\s*:\\s*\"([^\"]+)\"");
            Matcher errorMatcher = errorPattern.matcher(response);
            if (errorMatcher.find()) {
                String errorMessage = errorMatcher.group(1);
                if (!errorMessage.equals("null")) {
                    context.setErrorMessage(errorMessage);
                }
            }
            
            // 提取bugRelated
            Pattern bugPattern = Pattern.compile("\"bugRelated\"\\s*:\\s*(true|false)");
            Matcher bugMatcher = bugPattern.matcher(response);
            if (bugMatcher.find()) {
                boolean bugRelated = Boolean.parseBoolean(bugMatcher.group(1));
                context.setBugRelated(bugRelated);
            }
            
            // 提取needBroadSearch
            Pattern broadPattern = Pattern.compile("\"needBroadSearch\"\\s*:\\s*(true|false)");
            Matcher broadMatcher = broadPattern.matcher(response);
            if (broadMatcher.find()) {
                boolean needBroadSearch = Boolean.parseBoolean(broadMatcher.group(1));
                context.setNeedBroadSearch(needBroadSearch);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析搜索上下文时出错: " + e.getMessage());
            // 添加默认关键词
            context.addKeyword(input);
        }
        
        // 确保至少有一个关键词
        if (context.getKeywords().isEmpty()) {
            context.addKeyword(input);
        }
        
        return context;
    }
    
    /**
     * 精确查找类文件
     */
    private Path findExactClass(String className) {
        // 如果已缓存，直接返回
        if (classPathCache.containsKey(className)) {
            return classPathCache.get(className);
        }
        
        // 去除包名，只保留简单类名
        String simpleClassName = className;
        if (className.contains(".")) {
            simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        }
        final String simpleClassNameFinal = simpleClassName;
        // 构建可能的类路径
        List<String> possiblePaths = new ArrayList<>();
        
        // 如果包含完整包名
        if (className.contains(".")) {
            possiblePaths.add(className.replace('.', '/') + ".java");
        }
        
        // 常见包的可能位置
        String[] commonPackages = {
            "java/lang/", "java/util/", "java/io/", "java/nio/",
            "java/net/", "java/security/", "java/text/", "java/time/",
            "java/sql/", "javax/swing/", "javax/net/", "javax/sql/"
        };
        
        for (String pkg : commonPackages) {
            possiblePaths.add(pkg + simpleClassName + ".java");
        }
        
        // 尝试所有可能的路径
        for (String path : possiblePaths) {
            Path filePath = Paths.get(sourceRootPath, path);
            if (Files.exists(filePath)) {
                // 缓存结果
                classPathCache.put(className, filePath);
                return filePath;
            }
        }
        
        // 如果没有找到精确匹配，尝试查找名称匹配的类
        try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
            Optional<Path> result = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.getFileName().toString().equals(simpleClassNameFinal + ".java"))
                .findFirst();
            
            if (result.isPresent()) {
                // 缓存结果
                classPathCache.put(className, result.get());
                return result.get();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "查找类文件时出错: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 查找指定类中的方法实现
     */
    private List<Path> findMethodImplementations(String className, String methodName) {
        List<Path> results = new ArrayList<>();
        Path classFile = findExactClass(className);
        
        if (classFile != null) {
            // 首先添加类本身
            results.add(classFile);
            
            // 查找子类和实现类
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
                // 读取类文件，查找类继承或实现关系
                String content = Files.readString(classFile);
                Pattern extendsPattern = Pattern.compile("class\\s+\\w+\\s+extends\\s+(\\w+)");
                Matcher extendsMatcher = extendsPattern.matcher(content);
                
                String parentClass = null;
                if (extendsMatcher.find()) {
                    parentClass = extendsMatcher.group(1);
                    Path parentFile = findExactClass(parentClass);
                    if (parentFile != null) {
                        results.add(parentFile);
                    }
                }
                
                // 查找类似的实现文件
                String simpleClassName = className;
                if (className.contains(".")) {
                    simpleClassName = className.substring(className.lastIndexOf('.') + 1);
                }
                
                // 查找包含该方法名的其他文件
                List<Path> methodFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !results.contains(p))
                    .filter(p -> {
                        try {
                            String fileContent = Files.readString(p);
                            // 检查文件是否包含该方法的实现
                            return fileContent.contains("void " + methodName + "(") || 
                                   fileContent.contains("boolean " + methodName + "(") ||
                                   fileContent.contains("int " + methodName + "(") ||
                                   fileContent.contains("String " + methodName + "(") ||
                                   fileContent.contains("Object " + methodName + "(") ||
                                   fileContent.contains(" " + methodName + "(");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .limit(3)
                    .collect(Collectors.toList());
                
                results.addAll(methodFiles);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "查找方法实现时出错: " + e.getMessage());
            }
        }
        
        return results;
    }
    
    /**
     * 查找与目标类相关的类
     */
    private List<Path> findRelatedClasses(String className) {
        List<Path> results = new ArrayList<>();
        String simpleClassName = className;
        if (className.contains(".")) {
            simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        }
        final String simpleClassNameFinal = simpleClassName;
        try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
            // 查找同包下的类
            Path classFile = findExactClass(className);
            if (classFile != null) {
                Path parentDir = classFile.getParent();
                if (parentDir != null) {
                    try (Stream<Path> packageFiles = Files.list(parentDir)) {
                        List<Path> relatedInPackage = packageFiles
                            .filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> !p.equals(classFile))
                            .limit(3)
                            .collect(Collectors.toList());
                        
                        results.addAll(relatedInPackage);
                    }
                }
                
                // 查找引用该类的类
                List<Path> referencingFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !results.contains(p) && !p.equals(classFile))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.contains("import " + className + ";") || 
                                   content.contains("new " + simpleClassNameFinal + "(") ||
                                   content.contains(simpleClassNameFinal + ".");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .limit(3)
                    .collect(Collectors.toList());
                
                results.addAll(referencingFiles);
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "查找相关类时出错: " + e.getMessage());
        }
        
        return results;
    }
    
    /**
     * 根据错误消息查找相关源码文件
     */
    private List<Path> findFilesWithErrorMessage(String errorMessage) {
        List<Path> results = new ArrayList<>();
        
        // 从错误消息中提取可能的类名和方法名
        Pattern classPattern = Pattern.compile("at\\s+([\\w\\.]+)\\.(\\w+)\\(");
        Matcher matcher = classPattern.matcher(errorMessage);
        Set<String> searchedClasses = new HashSet<>();
        
        while (matcher.find() && results.size() < 3) {
            String className = matcher.group(1);
            String methodName = matcher.group(2);
            
            if (!searchedClasses.contains(className)) {
                searchedClasses.add(className);
                
                Path classFile = findExactClass(className);
                if (classFile != null) {
                    results.add(classFile);
                }
            }
        }
        
        // 如果没有从错误堆栈中找到类，尝试在源码中查找包含错误消息的文件
        if (results.isEmpty()) {
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
                // 创建更简短的错误消息版本，通常用于消息常量
                String shortError = errorMessage;
                if (shortError.length() > 15) {
                    shortError = shortError.substring(0, 15);
                }
                final String shortErrorFinal = shortError;
                List<Path> errorFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.contains(shortErrorFinal);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .limit(2)
                    .toList();
                
                results.addAll(errorFiles);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "查找错误消息相关文件时出错: " + e.getMessage());
            }
        }
        
        return results;
    }
    
    /**
     * 根据关键词在源码库中查找相关文件
     */
    private List<Path> findFilesByKeyword(String keyword, SearchContext context) throws IOException {
        List<Path> results = new ArrayList<>();
        
        // 首先查找文件名包含关键词的文件
        try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath))) {
            List<Path> fileNameMatches = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.getFileName().toString().toLowerCase().contains(keyword.toLowerCase()))
                .limit(context.isBugRelated() ? 5 : 3)
                .toList();
            
            results.addAll(fileNameMatches);
        }
        
        // 如果文件名匹配不足，则搜索文件内容
        int contentSearchLimit = context.isBugRelated() ? 5 : 3;
        if (results.size() < contentSearchLimit) {
            // 为了性能，限制内容搜索的深度
            try (Stream<Path> paths = Files.walk(Paths.get(sourceRootPath), 12)) {
                int remainingLimit = contentSearchLimit - results.size();
                List<Path> contentMatches = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !results.contains(p))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.toLowerCase().contains(keyword.toLowerCase());
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
    
    /**
     * 获取文件相对于源码根目录的路径
     */
    private String getRelativePath(Path file) {
        return Paths.get(sourceRootPath).relativize(file).toString();
    }
    
    /**
     * 高亮显示方法定义和使用
     */
    private String highlightMethod(String content, String methodName) {
        // 简单的方法高亮，在方法定义周围添加特殊标记
        StringBuilder sb = new StringBuilder(content);
        Pattern methodPattern = Pattern.compile("(public|protected|private)?\\s+[\\w<>\\[\\]]+\\s+" + 
                                              methodName + "\\s*\\([^\\{]*\\{");
        Matcher matcher = methodPattern.matcher(content);
        
        // 记录偏移量，因为插入标记会改变字符串长度
        int offset = 0;
        
        while (matcher.find()) {
            int start = matcher.start() + offset;
            int end = matcher.end() + offset;
            
            // 在方法定义前插入标记
            sb.insert(start, "/* 【方法定义开始】 */ ");
            offset += 21; // 更新偏移量
            
            // 在方法定义后插入标记
            sb.insert(end, " /* 【方法定义结束】 */");
            offset += 22; // 更新偏移量
        }
        
        return sb.toString();
    }
    
    /**
     * 高亮显示文本
     */
    private String highlightText(String content, String text) {
        // 在匹配文本周围添加特殊标记
        if (text == null || text.isEmpty()) return content;
        
        String simplifiedText = text;
        if (text.length() > 15) {
            simplifiedText = text.substring(0, 15);
        }
        
        StringBuilder sb = new StringBuilder(content);
        Pattern pattern = Pattern.compile(Pattern.quote(simplifiedText));
        Matcher matcher = pattern.matcher(content);
        
        int offset = 0;
        while (matcher.find()) {
            int start = matcher.start() + offset;
            int end = matcher.end() + offset;
            
            // 在文本前插入标记
            sb.insert(start, "/* 【相关文本】 */ ");
            offset += 18; // 更新偏移量
            
            // 在文本后插入标记
            sb.insert(end, " /* 【相关文本结束】 */");
            offset += 21; // 更新偏移量
        }
        
        return sb.toString();
    }
    
    /**
     * 搜索上下文类，存储搜索的相关信息
     */
    private static class SearchContext {
        private String targetClass;
        private String targetMethod;
        private List<String> keywords = new ArrayList<>();
        private String errorMessage;
        private boolean bugRelated = false;
        private boolean needBroadSearch = false;
        
        public String getTargetClass() {
            return targetClass;
        }
        
        public void setTargetClass(String targetClass) {
            this.targetClass = targetClass;
        }
        
        public String getTargetMethod() {
            return targetMethod;
        }
        
        public void setTargetMethod(String targetMethod) {
            this.targetMethod = targetMethod;
        }
        
        public List<String> getKeywords() {
            return keywords;
        }
        
        public void addKeyword(String keyword) {
            this.keywords.add(keyword);
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
        
        public boolean isBugRelated() {
            return bugRelated;
        }
        
        public void setBugRelated(boolean bugRelated) {
            this.bugRelated = bugRelated;
        }
        
        public boolean isNeedBroadSearch() {
            return needBroadSearch;
        }
        
        public void setNeedBroadSearch(boolean needBroadSearch) {
            this.needBroadSearch = needBroadSearch;
        }
    }
    
    /**
     * 替代ArrayList，防止重复添加
     */
    private static class ArrayList<T> extends java.util.ArrayList<T> {
        @Override
        public boolean add(T element) {
            if (!this.contains(element)) {
                return super.add(element);
            }
            return false;
        }
    }
} 