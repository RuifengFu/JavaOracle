package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * JavaDoc文档检索工具 - 增强版
 * 支持分析上下文指导、内容处理、缓存机制
 */
@Deprecated
public class JavaDocSearchTool implements Tool<String> {
    private final String javadocBasePath;
    private final OpenAI llm;
    private final ApiInfoProcessor docProcessor;
    private final ContentProcessor contentProcessor;
    
    /**
     * 创建JavaDoc检索工具
     * @param javadocBasePath JavaDoc的根目录路径
     */
    public JavaDocSearchTool(String javadocBasePath) {
        this.javadocBasePath = javadocBasePath;
        this.llm = OpenAI.R1;
        this.docProcessor = new ApiInfoProcessor(javadocBasePath);
        this.contentProcessor = new ContentProcessor(javadocBasePath + "/doc_cache");
    }

    public JavaDocSearchTool() {
        this(GlobalConfig.getBaseDocPath());
        LoggerUtil.logExec(Level.INFO, "使用默认JavaDoc路径: " + javadocBasePath);
    }

    @Override
    public String getName() {
        return "javadoc_search";
    }
    
    @Override
    public String getDescription() {
        return "Search for relevant documents in JavaDoc based on the provided natural language query. An optional analysis context can be provided to optimize search results.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("query", "analysis_context");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
            "query", "Search query, which can be a class name, method name, or natural language description.",
            "analysis_context", "(Optional) Provide additional context (e.g., about a specific bug or feature) to find more relevant information."
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
            "query", "string",
            "analysis_context", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("query")) {
            return ToolResponse.failure("参数错误，必须提供 'query'");
        }
        String query = (String) args.get("query");

        return executeWithContext(query, null);
    }

    public ToolResponse<String> execute(String query) {
        return executeWithContext(query, null);
    }
    
    /**
     * 执行带分析上下文的搜索
     * @param input 搜索输入
     * @param analysisContext 分析上下文，用于指导搜索和内容处理
     * @return 处理后的JavaDoc内容
     */
    public ToolResponse<String> executeWithContext(String input, String analysisContext) {
        try {
            // 查找API文档根目录
            Path apiRootPath = findApiRootDir();
            if (apiRootPath == null) {
                return ToolResponse.failure("无法找到有效的JavaDoc API根目录");
            }
            
            // 使用LLM找到可能的JavaDoc路径并进行有效性过滤
            List<String> potentialPaths = findPotentialPaths(input, apiRootPath.toString(), analysisContext);
            
            // 检查是否找到了可能的路径
            if (potentialPaths.isEmpty()) {
                return ToolResponse.failure("未能找到与'" + input + "'相关的JavaDoc路径");
            }
            
            // 读取和处理文档
            StringBuilder finalContent = new StringBuilder();
            List<String> processedSources = new ArrayList<>();
            
            for (String path : potentialPaths) {
                try {
                    String rawContent = extractDocumentContent(apiRootPath, path);
                    if (rawContent != null && !rawContent.trim().isEmpty()) {
                        
                        // 如果有分析上下文，使用ContentProcessor进行智能处理
                        if (analysisContext != null && !analysisContext.trim().isEmpty()) {
                            List<ContentProcessor.ProcessedContentChunk> chunks = 
                                contentProcessor.processContent("javadoc:" + path, rawContent, analysisContext);
                            
                            if (!chunks.isEmpty()) {
                                finalContent.append("=== ").append(path).append(" (智能处理) ===\n\n");
                                
                                for (ContentProcessor.ProcessedContentChunk chunk : chunks) {
                                    finalContent.append(chunk.getFormattedContent()).append("\n");
                                }
                                
                                processedSources.add(path + " (处理后片段: " + chunks.size() + ")");
                                LoggerUtil.logExec(Level.INFO, "JavaDoc智能处理: " + path + 
                                                  " -> " + chunks.size() + " 个相关片段");
                            }
                        } else {
                            // 无分析上下文时，进行基础处理
                            finalContent.append("=== ").append(path).append(" (基础处理) ===\n");
                            String processedContent = processBasicContent(rawContent, input);
                            finalContent.append(processedContent).append("\n\n");
                            
                            processedSources.add(path + " (基础处理)");
                        }
                        
                        // 限制总内容长度
                        if (finalContent.length() > 50000) {
                            finalContent.append("...内容过多，已截断");
                            break;
                        }
                    }
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "处理JavaDoc路径时出错: " + path + " - " + e.getMessage());
                }
            }
            
            if (finalContent.length() == 0) {
                return ToolResponse.failure("找到了路径但无法读取或处理文档内容");
            }
            
            // 添加搜索摘要
            StringBuilder summary = new StringBuilder();
            summary.append("## JavaDoc搜索摘要\n\n");
            summary.append("- **搜索输入**: ").append(input).append("\n");
            summary.append("- **分析上下文**: ").append(analysisContext != null ? "已提供" : "未提供").append("\n");
            summary.append("- **处理的文档**: ").append(processedSources.size()).append("个\n");
            summary.append("- **文档列表**:\n");
            for (String source : processedSources) {
                summary.append("  - ").append(source).append("\n");
            }
            summary.append("\n---\n\n");
            
            finalContent.insert(0, summary.toString());
            
            return ToolResponse.success(finalContent.toString());
        } catch (Exception e) {
            return ToolResponse.failure("JavaDoc检索失败: " + e.getMessage());
        }
    }
    
    /**
     * 提取文档内容
     */
    private String extractDocumentContent(Path apiRootPath, String path) throws Exception {
        StringBuilder content = new StringBuilder();
        
        // 可能是类文件
        if (path.endsWith(".html")) {
            File docFile = new File(apiRootPath.toString(), path);
            if (!docFile.exists()) {
                // 尝试其他可能的路径变体
                docFile = findExistingDocFile(apiRootPath, path);
            }
            
            if (docFile != null && docFile.exists()) {
                // 解析HTML并转换为Markdown格式
                String htmlContent = processHtmlFileToMarkdown(docFile);
                content.append(htmlContent);
            }
        } 
        // 可能是包目录
        else {
            List<File> htmlFiles = findHtmlFilesInPackage(apiRootPath, path);
            
            for (File htmlFile : htmlFiles) {
                // 解析HTML并转换为Markdown格式
                String htmlContent = processHtmlFileToMarkdown(htmlFile);
                content.append("### ").append(htmlFile.getName()).append("\n\n");
                content.append(htmlContent).append("\n\n");
                
                // 限制单个包的内容量
                if (content.length() > 20000) {
                    content.append("...包内容过多，已截断");
                    break;
                }
            }
            
            // 检查是否有class-use目录
            File classUseDir = new File(apiRootPath.toString(), path + "/class-use");
            if (classUseDir.exists() && classUseDir.isDirectory()) {
                content.append("## 使用示例文档\n\n");
                File[] useFiles = classUseDir.listFiles((dir, name) -> name.endsWith(".html"));
                if (useFiles != null) {
                    for (File useFile : useFiles) {
                        String htmlContent = processHtmlFileToMarkdown(useFile);
                        content.append("### ").append(useFile.getName()).append("\n");
                        content.append(extractUsageSummary(htmlContent)).append("\n\n");
                        
                        // 限制使用文档量
                        if (content.length() > 30000) {
                            content.append("...使用文档过多，已截断");
                            break;
                        }
                    }
                }
            }
        }
        
        return content.toString();
    }
    
    /**
     * 处理HTML文件并转换为Markdown格式（保留原始表达）
     */
    private String processHtmlFileToMarkdown(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        
        // 移除导航和不必要的元素
        doc.select("nav, .topNav, .bottomNav, .subNav, script, style, footer").remove();
        
        StringBuilder markdown = new StringBuilder();
        
        // 提取标题
        Elements titles = doc.select("h1, h2, h3, .title");
        if (!titles.isEmpty()) {
            markdown.append("# ").append(titles.first().text()).append("\n\n");
        }
        
        // 提取类描述
        Elements classDesc = doc.select(".classDescription, #class-description, .block");
        for (int i = 0; i < Math.min(classDesc.size(), 3); i++) {
            String text = classDesc.get(i).text();
            if (!text.trim().isEmpty() && text.length() > 20) {
                markdown.append(text).append("\n\n");
            }
        }
        
        // 提取方法摘要
        Elements methodSummary = doc.select(".methodSummary .memberSummary tr");
        if (!methodSummary.isEmpty()) {
            markdown.append("## 方法摘要\n\n");
            for (int i = 0; i < Math.min(methodSummary.size(), 10); i++) {
                String methodText = methodSummary.get(i).text();
                if (!methodText.trim().isEmpty() && methodText.length() > 10) {
                    markdown.append("- ").append(methodText).append("\n");
                }
            }
            markdown.append("\n");
        }
        
        // 提取详细信息
        Elements details = doc.select(".details .memberSummary, .methodDetails .member");
        if (!details.isEmpty()) {
            markdown.append("## 详细信息\n\n");
            for (int i = 0; i < Math.min(details.size(), 5); i++) {
                String detailText = details.get(i).text();
                if (!detailText.trim().isEmpty() && detailText.length() > 30) {
                    markdown.append(detailText).append("\n\n");
                }
            }
        }
        
        // 如果提取的内容太少，使用全部文本内容
        if (markdown.length() < 200) {
            Elements mainContent = doc.select(".contentContainer, .classDescription, .details");
            if (!mainContent.isEmpty()) {
                return mainContent.text();
            } else {
                return doc.body().text();
            }
        }
        
        return markdown.toString();
    }
    
    /**
     * 基础内容处理（当没有分析上下文时）
     */
    private String processBasicContent(String content, String searchInput) {
        // 高亮搜索关键词
        String[] keywords = searchInput.toLowerCase().split("\\s+");
        String highlightedContent = content;
        
        for (String keyword : keywords) {
            if (keyword.length() > 2) {
                highlightedContent = highlightedContent.replaceAll(
                    "(?i)(" + keyword + ")", 
                    "**$1**"
                );
            }
        }
        
        // 限制长度
        if (highlightedContent.length() > 5000) {
            // 尝试找到与搜索词相关的段落
            String[] paragraphs = highlightedContent.split("\n\n");
            StringBuilder relevantContent = new StringBuilder();
            
            for (String paragraph : paragraphs) {
                boolean isRelevant = false;
                for (String keyword : keywords) {
                    if (paragraph.toLowerCase().contains(keyword.toLowerCase())) {
                        isRelevant = true;
                        break;
                    }
                }
                
                if (isRelevant) {
                    relevantContent.append(paragraph).append("\n\n");
                    if (relevantContent.length() > 3000) break;
                }
            }
            
            if (relevantContent.length() > 0) {
                return relevantContent.toString();
            } else {
                return highlightedContent.substring(0, 3000) + "...";
            }
        }
        
        return highlightedContent;
    }
    
    /**
     * 使用LLM找到可能的JavaDoc路径并进行有效性过滤
     */
    private List<String> findPotentialPaths(String input, String apiRootPath, String analysisContext) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("根据查询'").append(input).append("'，列出可能的JavaDoc文档路径。\n");
        
        if (analysisContext != null && !analysisContext.trim().isEmpty()) {
            promptBuilder.append("\n分析上下文:\n").append(analysisContext).append("\n");
            promptBuilder.append("请特别关注上下文中提到的相关类、API和问题。\n");
        }
        
        promptBuilder.append("\nJavaDoc根目录: ").append(apiRootPath).append("\n");
        promptBuilder.append("仅列出相对于根目录的路径，每行一个。路径必须是.html文件或有效目录。\n");
        promptBuilder.append("优先考虑与问题最相关的文档。\n");
        promptBuilder.append("示例格式:\njava/lang/String.html\njava/util\njava/util/HashMap.html\n");
        
        String rawResponse = llm.messageCompletion(promptBuilder.toString(), 0.0);
        
        // 1. 过滤思维链
        rawResponse = filterThinkingChain(rawResponse);
        
        // 2. 分割为行并过滤有效路径
        List<String> filteredPaths = new ArrayList<>();
        String[] lines = rawResponse.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            
            // 忽略空行和注释
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || 
                line.startsWith("-") || line.contains(":")) continue;
                
            // 验证是HTML文件或看起来像目录路径
            boolean isHtmlFile = line.endsWith(".html");
            boolean looksLikeDirectory = !line.contains(".") && 
                                        !line.contains("(") && 
                                        !line.contains(" ");
                                        
            if (isHtmlFile || looksLikeDirectory) {
                // 规范化路径格式
                while (line.startsWith("/") || line.startsWith(".")) {
                    line = line.substring(1);
                }
                
                // 验证文件或目录是否存在
                File testPath = new File(apiRootPath, line);
                if (testPath.exists()) {
                    filteredPaths.add(line);
                    continue;
                }
                
                // 尝试常见的变体
                if (isHtmlFile) {
                    String fileName = new File(line).getName();
                    String parentPath = line.substring(0, line.length() - fileName.length());
                    
                    // 首字母大写变体
                    String capitalizedName = fileName.substring(0, 1).toUpperCase() + 
                                           fileName.substring(1);
                    File capitalizedPath = new File(apiRootPath, parentPath + capitalizedName);
                    if (capitalizedPath.exists()) {
                        filteredPaths.add(parentPath + capitalizedName);
                    }
                }
            }
        }
        
        // 记录找到的有效路径
        LoggerUtil.logExec(Level.INFO, "为查询'" + input + "'找到" + filteredPaths.size() + 
                          "个有效路径" + (analysisContext != null ? " (有上下文指导)" : ""));
        
        return filteredPaths;
    }
    
    /**
     * 查找API文档根目录
     */
    private Path findApiRootDir() {
        // 检查可能的API根目录路径
        Path basePath = Paths.get(javadocBasePath);
        
        // 1. 检查 /docs/api 目录
        Path docsApiPath = basePath.resolve("docs").resolve("api");
        if (Files.exists(docsApiPath) && Files.isDirectory(docsApiPath)) {
            return docsApiPath;
        }
        
        // 2. 检查 /api 目录
        Path apiPath = basePath.resolve("api");
        if (Files.exists(apiPath) && Files.isDirectory(apiPath)) {
            return apiPath;
        }
        
        // 3. 使用基础目录本身
        if (Files.exists(basePath) && Files.isDirectory(basePath)) {
            return basePath;
        }
        
        return null;
    }
    
    /**
     * 查找指定模块和包中的HTML文件
     */
    private List<File> findHtmlFilesInPackage(Path apiRoot, String packagePath) {
        List<File> results = new ArrayList<>();
        
        // 常规包目录（如 java/util）
        File packageDir = new File(apiRoot.toString(), packagePath);
        if (packageDir.exists() && packageDir.isDirectory()) {
            File[] htmlFiles = packageDir.listFiles((dir, name) -> name.endsWith(".html") && !name.equals("package-summary.html"));
            if (htmlFiles != null) {
                for (File file : htmlFiles) {
                    results.add(file);
                }
            }
            
            // 添加package-summary.html（如果存在）作为最后一个
            File packageSummary = new File(packageDir, "package-summary.html");
            if (packageSummary.exists()) {
                results.add(packageSummary);
            }
        }
        
        // 如果未找到任何文件，尝试查找模块内的包
        if (results.isEmpty()) {
            try {
                // 列出所有可能的模块
                File[] moduleDirectories = apiRoot.toFile().listFiles(File::isDirectory);
                if (moduleDirectories != null) {
                    for (File moduleDir : moduleDirectories) {
                        // 检查每个模块中是否有匹配的包路径
                        File modulePackageDir = new File(moduleDir, packagePath);
                        if (modulePackageDir.exists() && modulePackageDir.isDirectory()) {
                            File[] htmlFiles = modulePackageDir.listFiles((dir, name) -> name.endsWith(".html"));
                            if (htmlFiles != null) {
                                for (File file : htmlFiles) {
                                    results.add(file);
                                    if (results.size() >= 10) break;  // 限制返回数量
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "查找模块内的包时出错: " + e.getMessage());
            }
        }
        
        return results;
    }
    
    /**
     * 尝试找到存在的文档文件（考虑不同的路径变体）
     */
    private File findExistingDocFile(Path apiRoot, String path) {
        // 原始路径尝试
        File originalFile = new File(apiRoot.toString(), path);
        if (originalFile.exists()) {
            return originalFile;
        }
        
        // 尝试在各个模块中查找
        File[] moduleDirectories = apiRoot.toFile().listFiles(File::isDirectory);
        if (moduleDirectories != null) {
            for (File moduleDir : moduleDirectories) {
                if (moduleDir.getName().startsWith("java.") || moduleDir.getName().startsWith("javax.")) {
                    File moduleFile = new File(moduleDir, path);
                    if (moduleFile.exists()) {
                        return moduleFile;
                    }
                }
            }
        }
        
        // 如果路径以package/ClassName.html形式给出，尝试转换为package-summary.html
        if (path.contains("/") && !path.endsWith("/package-summary.html")) {
            String packagePath = path.substring(0, path.lastIndexOf('/'));
            File packageSummary = new File(apiRoot.toString(), packagePath + "/package-summary.html");
            if (packageSummary.exists()) {
                return packageSummary;
            }
        }
        
        return null;
    }
    
    /**
     * 从使用文档中提取摘要信息
     */
    private String extractUsageSummary(String htmlContent) {
        // 为了简洁，只提取前500个字符
        if (htmlContent.length() > 500) {
            return htmlContent.substring(0, 500) + "...";
        }
        return htmlContent;
    }
    
    /**
     * 过滤思维链标记
     */
    private String filterThinkingChain(String content) {
        if (content == null) return "";
        
        // 移除<think>...</think>块
        String filtered = content.replaceAll("(?s)<think>.*?</think>", "");
        
        // 移除其他可能的思维链标记
        filtered = filtered.replaceAll("(?s)```thinking.*?```", "");
        filtered = filtered.replaceAll("(?s)\\[思考\\].*?\\[/思考\\]", "");
        
        return filtered.trim();
    }
} 