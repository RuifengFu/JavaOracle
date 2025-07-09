package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * 简化的JavaDoc搜索工具 - 只提供基础静态文档检索功能
 * Agent负责智能决策，工具只负责执行具体的文档查找操作
 */
public class SimplifiedJavaDocSearchTool implements Tool<String> {
    private final String javadocBasePath;
    private Path apiRootPath;
    
    public SimplifiedJavaDocSearchTool(String javadocBasePath) {
        this.javadocBasePath = javadocBasePath;
        this.apiRootPath = findApiRootDir();
    }
    
    public SimplifiedJavaDocSearchTool() {
        this(GlobalConfig.getBaseDocPath());
    }
    
    @Override
    public String getName() {
        return "simplified_javadoc_search";
    }
    
    @Override
    public String getDescription() {
        return "静态JavaDoc文档搜索工具 - 在JDK API文档中查找类文档、包信息和API说明。支持按类名查找、按路径访问、包浏览和文档结构探索。";
    }
    
    @Override
    public List<String> getParameters() {
        return List.of("search_type", "doc_path", "class_name", "package_name");
    }
    
    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
            "search_type", "搜索类型 (必填):\n" +
                          "• 'by_path' - 按文档路径直接访问HTML文档\n" +
                          "• 'by_class' - 按类名查找API文档\n" +
                          "• 'by_package' - 列出指定包中的所有类文档\n" +
                          "• 'list_files' - 浏览文档目录结构",
            
            "doc_path", "文档路径 (用于by_path):\n" +
                       "• HTML文档的相对路径，如 'java/util/HashMap.html'\n" +
                       "• 支持简化路径，如 'HashMap.html'\n" +
                       "• 支持JDK模块化路径，如 'java.base/java/util/HashMap.html'\n" +
                       "• 工具会自动尝试多种路径格式",
            
            "class_name", "类名 (用于by_class):\n" +
                         "• 推荐使用完整类名，如 'java.util.HashMap'\n" +
                         "• 支持简单类名，如 'HashMap'\n" +
                         "• 会在常见包路径中自动查找\n" +
                         "• 返回类的完整API文档",
            
            "package_name", "包名 (用于by_package和list_files):\n" +
                           "• 完整包名，如 'java.util', 'java.io'\n" +
                           "• 用于by_package时列出包中所有类文档\n" +
                           "• 用于list_files时浏览指定包的目录结构\n" +
                           "• 为空时list_files显示根目录结构"
        );
    }
    
    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
            "search_type", "string (enum: by_path|by_class|by_package|list_files)",
            "doc_path", "string (optional, required for by_path)",
            "class_name", "string (optional, required for by_class)",
            "package_name", "string (optional, required for by_package, optional for list_files)"
        );
    }
    
    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        try {
            if (apiRootPath == null) {
                return ToolResponse.failure("无法找到有效的JavaDoc API根目录");
            }
            
            String searchType = (String) args.get("search_type");
            if (searchType == null) {
                return ToolResponse.failure("必须指定search_type参数");
            }
            
            switch (searchType) {
                case "by_path":
                    return getDocByPath((String) args.get("doc_path"));
                case "by_class":
                    return searchDocByClassName((String) args.get("class_name"));
                case "by_package":
                    return listPackageClasses((String) args.get("package_name"));
                case "list_files":
                    return listAvailableFiles((String) args.get("package_name"));
                default:
                    return ToolResponse.failure("不支持的搜索类型: " + searchType);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "JavaDoc搜索失败: " + e.getMessage());
            return ToolResponse.failure("搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 按路径获取文档内容
     */
    public ToolResponse<String> getDocByPath(String docPath) {
        if (docPath == null || docPath.trim().isEmpty()) {
            return ToolResponse.failure("文档路径不能为空");
        }
        
        try {
            File docFile = findExistingDocFile(docPath);
            
            if (docFile == null || !docFile.exists()) {
                return ToolResponse.failure("文档文件不存在: " + docPath + 
                    "\n提示: 请使用 'list_files' 查看可用的文档结构");
            }
            
            String content = parseHtmlToMarkdown(docFile);
            
            StringBuilder result = new StringBuilder();
            result.append("## 文档: ").append(docPath).append("\n\n");
            result.append("**实际路径**: ").append(getRelativePath(docFile)).append("\n\n");
            result.append(content);
            
            return ToolResponse.success(result.toString());
        } catch (Exception e) {
            return ToolResponse.failure("读取文档失败: " + e.getMessage());
        }
    }
    
    /**
     * 按类名查找文档
     */
    public ToolResponse<String> searchDocByClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("类名不能为空");
        }
        
        try {
            List<String> possiblePaths = generateClassDocPaths(className);
            
            for (String path : possiblePaths) {
                File docFile = new File(apiRootPath.toString(), path);
                if (docFile.exists()) {
                    String content = parseHtmlToMarkdown(docFile);
                    
                    StringBuilder result = new StringBuilder();
                    result.append("## 类文档: ").append(className).append("\n\n");
                    result.append("**文档路径**: ").append(path).append("\n\n");
                    result.append(content);
                    
                    return ToolResponse.success(result.toString());
                }
            }
            
            return ToolResponse.failure("未找到类 " + className + " 的文档");
        } catch (Exception e) {
            return ToolResponse.failure("搜索类文档失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出包下的所有类文档
     */
    public ToolResponse<String> listPackageClasses(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return ToolResponse.failure("包名不能为空");
        }
        
        try {
            String packagePath = packageName.replace('.', '/');
            List<File> htmlFiles = findHtmlFilesInPackage(packagePath);
            
            if (htmlFiles.isEmpty()) {
                return ToolResponse.failure("包 " + packageName + " 中未找到文档文件");
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## 包 ").append(packageName).append(" 中的类文档\n\n");
            result.append("找到 ").append(htmlFiles.size()).append(" 个类文档:\n\n");
            
            for (File htmlFile : htmlFiles) {
                String relativePath = getRelativePath(htmlFile);
                String className = htmlFile.getName().replace(".html", "");
                
                result.append("### ").append(className).append("\n");
                result.append("**路径**: ").append(relativePath).append("\n\n");
                
                // 提取简要信息
                try {
                    String briefInfo = extractBriefInfo(htmlFile);
                    result.append(briefInfo).append("\n\n");
                } catch (Exception e) {
                    result.append("*无法提取简要信息*\n\n");
                }
                
                if (result.length() > 10000) { // 限制输出长度
                    result.append("...结果过多，已截断");
                    break;
                }
            }
            
            return ToolResponse.success(result.toString());
        } catch (Exception e) {
            return ToolResponse.failure("列出包文档失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出可用的文件和目录结构
     */
    public ToolResponse<String> listAvailableFiles(String basePath) {
        try {
            Path searchPath = apiRootPath;
            if (basePath != null && !basePath.trim().isEmpty()) {
                searchPath = apiRootPath.resolve(basePath.replace('.', '/'));
            }
            
            if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
                return ToolResponse.failure("指定的路径不存在或不是目录: " + basePath);
            }
            
            StringBuilder result = new StringBuilder();
            result.append("## 目录结构: ").append(basePath != null ? basePath : "根目录").append("\n\n");
            
            // 列出目录
            List<Path> directories = new ArrayList<>();
            List<Path> htmlFiles = new ArrayList<>();
            
            try (var stream = Files.list(searchPath)) {
                stream.forEach(path -> {
                    if (Files.isDirectory(path)) {
                        directories.add(path);
                    } else if (path.toString().endsWith(".html")) {
                        htmlFiles.add(path);
                    }
                });
            }
            
            // 显示目录
            if (!directories.isEmpty()) {
                result.append("### 子目录\n\n");
                directories.stream()
                          .sorted()
                          .limit(20)
                          .forEach(dir -> result.append("- **").append(dir.getFileName()).append("/**\n"));
                result.append("\n");
            }
            
            // 显示HTML文件
            if (!htmlFiles.isEmpty()) {
                result.append("### 文档文件\n\n");
                htmlFiles.stream()
                         .sorted()
                         .limit(30)
                         .forEach(file -> {
                             String fileName = file.getFileName().toString();
                             String className = fileName.replace(".html", "");
                             result.append("- **").append(className).append("** (").append(fileName).append(")\n");
                         });
            }
            
            if (directories.isEmpty() && htmlFiles.isEmpty()) {
                result.append("*该目录为空或不包含文档文件*");
            }
            
            return ToolResponse.success(result.toString());
        } catch (Exception e) {
            return ToolResponse.failure("列出文件失败: " + e.getMessage());
        }
    }
    
    // ===================== 私有辅助方法 =====================
    
    /**
     * 查找API文档根目录
     */
    private Path findApiRootDir() {
        Path basePath = Paths.get(javadocBasePath);
        
        // 检查 /docs/api 目录
        Path docsApiPath = basePath.resolve("docs").resolve("api");
        if (Files.exists(docsApiPath) && Files.isDirectory(docsApiPath)) {
            return docsApiPath;
        }
        
        // 检查 /api 目录
        Path apiPath = basePath.resolve("api");
        if (Files.exists(apiPath) && Files.isDirectory(apiPath)) {
            return apiPath;
        }
        
        // 使用基础目录
        if (Files.exists(basePath) && Files.isDirectory(basePath)) {
            return basePath;
        }
        
        return null;
    }
    
    /**
     * 生成类文档的可能路径
     */
    private List<String> generateClassDocPaths(String className) {
        List<String> paths = new ArrayList<>();
        
        String simpleClassName = className.contains(".") ? 
            className.substring(className.lastIndexOf('.') + 1) : className;
        
        // 1. 如果有完整包名，生成直接路径
        if (className.contains(".")) {
            String packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
            paths.add(packagePath + "/" + simpleClassName + ".html");
            
            // JDK模块结构路径
            if (className.startsWith("java.") || className.startsWith("javax.")) {
                String[] parts = className.split("\\.");
                if (parts.length >= 2) {
                    String moduleName = parts[0] + "." + parts[1]; // 如 java.base
                    paths.add(moduleName + "/" + packagePath + "/" + simpleClassName + ".html");
                }
            }
        }
        
        // 2. 常见包路径
        String[] commonPackages = {
            "java/lang", "java/util", "java/io", "java/nio",
            "java/net", "java/security", "java/text", "java/time",
            "java/util/concurrent", "java/util/stream", "java/util/function",
            "javax/swing", "javax/net", "javax/security"
        };
        
        for (String pkg : commonPackages) {
            paths.add(pkg + "/" + simpleClassName + ".html");
        }
        
        // 3. 模块化路径（JDK 9+）
        if (className.startsWith("java.")) {
            String[] parts = className.split("\\.");
            if (parts.length >= 2) {
                String moduleName = parts[0] + "." + parts[1];
                String packagePath = String.join("/", Arrays.copyOfRange(parts, 0, parts.length - 1));
                paths.add(moduleName + "/" + packagePath + "/" + simpleClassName + ".html");
            }
        }
        
        return paths;
    }
    
    /**
     * 查找包中的HTML文件
     */
    private List<File> findHtmlFilesInPackage(String packagePath) {
        List<File> results = new ArrayList<>();
        
        File packageDir = new File(apiRootPath.toString(), packagePath);
        if (packageDir.exists() && packageDir.isDirectory()) {
            File[] htmlFiles = packageDir.listFiles((dir, name) -> 
                name.endsWith(".html") && !name.equals("package-summary.html") && !name.equals("package-tree.html"));
            
            if (htmlFiles != null) {
                Arrays.sort(htmlFiles, (a, b) -> a.getName().compareTo(b.getName()));
                results.addAll(Arrays.asList(htmlFiles));
            }
        }
        
        // 如果在根目录没找到，尝试在模块目录中查找
        if (results.isEmpty()) {
            File[] moduleDirectories = apiRootPath.toFile().listFiles(File::isDirectory);
            if (moduleDirectories != null) {
                for (File moduleDir : moduleDirectories) {
                    File modulePackageDir = new File(moduleDir, packagePath);
                    if (modulePackageDir.exists() && modulePackageDir.isDirectory()) {
                        File[] htmlFiles = modulePackageDir.listFiles((dir, name) -> name.endsWith(".html"));
                        if (htmlFiles != null) {
                            results.addAll(Arrays.asList(htmlFiles));
                            if (results.size() >= 20) break; // 限制数量
                        }
                    }
                }
            }
        }
        
        return results;
    }
    
    /**
     * 尝试找到存在的文档文件
     */
    private File findExistingDocFile(String docPath) {
        // 1. 直接路径
        File directFile = new File(apiRootPath.toString(), docPath);
        if (directFile.exists()) {
            return directFile;
        }
        
        // 2. 生成可能的路径变体
        List<String> possiblePaths = new ArrayList<>();
        possiblePaths.add(docPath);
        
        // 如果是简单的类路径，尝试添加 .html 扩展名
        if (!docPath.endsWith(".html") && !docPath.contains("/")) {
            possiblePaths.add(docPath + ".html");
        }
        
        // 如果是 java/util/HashMap.html 格式，尝试模块化路径
        if (docPath.startsWith("java/") || docPath.startsWith("javax/")) {
            String[] parts = docPath.split("/");
            if (parts.length >= 2) {
                String moduleName = parts[0] + "." + parts[1]; // java.base, java.util 等
                possiblePaths.add(moduleName + "/" + docPath);
            }
        }
        
        // 3. 尝试所有可能的路径
        for (String path : possiblePaths) {
            File file = new File(apiRootPath.toString(), path);
            if (file.exists()) {
                LoggerUtil.logExec(Level.INFO, "找到文档文件: " + docPath + " -> " + path);
                return file;
            }
        }
        
        // 4. 在模块目录中查找
        File[] moduleDirectories = apiRootPath.toFile().listFiles(File::isDirectory);
        if (moduleDirectories != null) {
            for (File moduleDir : moduleDirectories) {
                if (moduleDir.getName().startsWith("java.") || moduleDir.getName().startsWith("javax.")) {
                    File moduleFile = new File(moduleDir, docPath);
                    if (moduleFile.exists()) {
                        LoggerUtil.logExec(Level.INFO, "在模块中找到文档文件: " + docPath + " -> " + moduleDir.getName() + "/" + docPath);
                        return moduleFile;
                    }
                }
            }
        }
        
        // 5. 智能搜索：根据文件名在整个文档目录中查找
        if (docPath.endsWith(".html")) {
            String fileName = docPath.substring(docPath.lastIndexOf('/') + 1);
            try {
                File foundFile = findFileByName(apiRootPath.toFile(), fileName);
                if (foundFile != null) {
                    LoggerUtil.logExec(Level.INFO, "通过文件名找到文档: " + docPath + " -> " + getRelativePath(foundFile));
                    return foundFile;
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "智能搜索文档文件时出错: " + e.getMessage());
            }
        }
        
        LoggerUtil.logExec(Level.WARNING, "未找到文档文件: " + docPath);
        return null;
    }
    
    /**
     * 递归查找指定名称的文件
     */
    private File findFileByName(File directory, String fileName) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        
        // 首先在当前目录查找
        for (File file : files) {
            if (file.isFile() && file.getName().equals(fileName)) {
                return file;
            }
        }
        
        // 然后在子目录中查找（限制深度避免性能问题）
        for (File file : files) {
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                File found = findFileByName(file, fileName);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 解析HTML为Markdown格式
     */
    private String parseHtmlToMarkdown(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        
        // 移除导航和无关元素
        doc.select("nav, .topNav, .bottomNav, .subNav, script, style, footer, .header").remove();
        
        StringBuilder markdown = new StringBuilder();
        
        // 提取标题
        Elements titles = doc.select("h1, h2, .title");
        if (!titles.isEmpty()) {
            markdown.append("# ").append(titles.first().text()).append("\n\n");
        }
        
        // 提取类描述
        Elements classDesc = doc.select(".description .block, .classDescription, #class-description");
        for (int i = 0; i < Math.min(classDesc.size(), 2); i++) {
            String text = classDesc.get(i).text().trim();
            if (!text.isEmpty() && text.length() > 20) {
                markdown.append(text).append("\n\n");
            }
        }
        
        // 提取方法摘要
        Elements methodSummary = doc.select(".summary table tr");
        if (methodSummary.size() > 1) { // 排除表头
            markdown.append("## 方法摘要\n\n");
            for (int i = 1; i < Math.min(methodSummary.size(), 11); i++) { // 跳过表头，最多10个方法
                String methodText = methodSummary.get(i).text().trim();
                if (!methodText.isEmpty() && methodText.length() > 10) {
                    // 简化方法描述
                    String[] parts = methodText.split("\\s+", 3);
                    if (parts.length >= 2) {
                        markdown.append("- **").append(parts[1]).append("**");
                        if (parts.length > 2) {
                            String desc = parts[2];
                            if (desc.length() > 100) {
                                desc = desc.substring(0, 100) + "...";
                            }
                            markdown.append(": ").append(desc);
                        }
                        markdown.append("\n");
                    }
                }
            }
            markdown.append("\n");
        }
        
        // 如果提取的内容太少，使用主要内容区域的文本
        if (markdown.length() < 200) {
            Elements mainContent = doc.select(".contentContainer, .details, main");
            if (!mainContent.isEmpty()) {
                String text = mainContent.first().text();
                if (text.length() > 1000) {
                    text = text.substring(0, 1000) + "...";
                }
                return text;
            }
        }
        
        return markdown.toString();
    }
    
    /**
     * 提取文档的简要信息
     */
    private String extractBriefInfo(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        
        // 提取第一段描述
        Elements descriptions = doc.select(".description .block, .classDescription");
        if (!descriptions.isEmpty()) {
            String text = descriptions.first().text().trim();
            if (text.length() > 150) {
                text = text.substring(0, 150) + "...";
            }
            return text;
        }
        
        // 如果没找到描述，返回类型信息
        Elements typeInfo = doc.select(".header h2, .title");
        if (!typeInfo.isEmpty()) {
            return "*" + typeInfo.first().text() + "*";
        }
        
        return "*无描述信息*";
    }
    
    /**
     * 获取相对路径
     */
    private String getRelativePath(File file) {
        return apiRootPath.relativize(file.toPath()).toString();
    }
} 