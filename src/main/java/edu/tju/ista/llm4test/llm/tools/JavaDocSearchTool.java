package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.ApiDocProcessor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * JavaDoc文档检索工具
 */
public class JavaDocSearchTool implements Tool<String> {
    private final String javadocBasePath;
    private final OpenAI llm;
    private final ApiDocProcessor docProcessor;
    
    /**
     * 创建JavaDoc检索工具
     * @param javadocBasePath JavaDoc的根目录路径
     */
    public JavaDocSearchTool(String javadocBasePath) {
        this.javadocBasePath = javadocBasePath;
        this.llm = OpenAI.R1;
        this.docProcessor = new ApiDocProcessor(javadocBasePath);
    }
    
    @Override
    public String getName() {
        return "javadoc_search";
    }
    
    @Override
    public String getDescription() {
        return "根据提供的关键词（类名、方法名等）在JavaDoc中检索相关文档";
    }
    
    @Override
    public ToolResponse<String> execute(String input) {
        try {
            // 查找API文档根目录
            Path apiRootPath = findApiRootDir();
            if (apiRootPath == null) {
                return ToolResponse.failure("无法找到有效的JavaDoc API根目录");
            }
            
            // 使用LLM找出可能的类路径
            String potentialPaths = findPotentialPaths(input, apiRootPath.toString());
            
            // 检查是否找到了可能的路径
            if (potentialPaths.isEmpty()) {
                return ToolResponse.failure("未能找到与'" + input + "'相关的JavaDoc路径");
            }
            
            // 读取文档
            StringBuilder docContent = new StringBuilder();
            String[] paths = potentialPaths.split("\n");
            
            for (String path : paths) {
                path = path.trim();
                if (path.isEmpty()) continue;
                
                try {
                    // 可能是类文件
                    if (path.endsWith(".html")) {
                        File docFile = new File(apiRootPath.toString(), path);
                        if (!docFile.exists()) {
                            // 尝试其他可能的路径变体
                            docFile = findExistingDocFile(apiRootPath, path);
                        }
                        
                        if (docFile != null && docFile.exists()) {
                            // 优先使用ApiDocProcessor处理文档
                            try {
                                String content = docProcessor.processApiDocs(docFile);
                                if (content != null && !content.trim().isEmpty()) {
                                    docContent.append("--- 来自文件: ").append(docFile.getPath()).append(" ---\n");
                                    docContent.append(content).append("\n\n");
                                    continue;
                                }
                            } catch (Exception e) {
                                // ApiDocProcessor失败时使用直接HTML解析作为后备
                                LoggerUtil.logExec(Level.WARNING, "ApiDocProcessor处理失败，使用HTML解析: " + e.getMessage());
                            }
                            
                            // 直接解析HTML
                            String htmlContent = processHtmlFile(docFile);
                            docContent.append("--- 来自文件: ").append(docFile.getPath()).append(" ---\n");
                            docContent.append(htmlContent).append("\n\n");
                        }
                    } 
                    // 可能是包目录
                    else {
                        List<File> htmlFiles = findHtmlFilesInPackage(apiRootPath, path);
                        
                        for (File htmlFile : htmlFiles) {
                            // 优先使用ApiDocProcessor
                            try {
                                String content = docProcessor.processApiDocs(htmlFile);
                                if (content != null && !content.trim().isEmpty()) {
                                    docContent.append("--- 来自目录文件: ").append(htmlFile.getPath()).append(" ---\n");
                                    docContent.append(content).append("\n\n");
                                    continue;
                                }
                            } catch (Exception e) {
                                // 失败时使用HTML解析
                            }
                            
                            // 直接解析HTML
                            String htmlContent = processHtmlFile(htmlFile);
                            docContent.append("--- 来自目录文件: ").append(htmlFile.getPath()).append(" ---\n");
                            docContent.append(htmlContent).append("\n\n");
                            
                            // 限制返回的文档量
                            if (docContent.length() > 10000) {
                                docContent.append("...文档内容过多，已截断");
                                break;
                            }
                        }
                        
                        // 检查是否有class-use目录
                        File classUseDir = new File(apiRootPath.toString(), path + "/class-use");
                        if (classUseDir.exists() && classUseDir.isDirectory()) {
                            docContent.append("--- 相关使用文档(class-use) ---\n");
                            File[] useFiles = classUseDir.listFiles((dir, name) -> name.endsWith(".html"));
                            if (useFiles != null) {
                                for (File useFile : useFiles) {
                                    String htmlContent = processHtmlFile(useFile);
                                    docContent.append("-- ").append(useFile.getName()).append(" --\n");
                                    docContent.append(extractUsageSummary(htmlContent)).append("\n");
                                    
                                    // 限制返回的文档量
                                    if (docContent.length() > 15000) {
                                        docContent.append("...使用文档过多，已截断");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "处理文档路径时出错: " + path + " - " + e.getMessage());
                }
            }
            
            if (docContent.length() == 0) {
                return ToolResponse.failure("找到了路径但无法读取文档内容");
            }
            
            return ToolResponse.success(docContent.toString());
        } catch (Exception e) {
            return ToolResponse.failure("文档检索失败: " + e.getMessage());
        }
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
        // JavaDoc可能使用模块结构，如java.base/java/util
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
     * 处理HTML文件内容，提取有用信息
     */
    private String processHtmlFile(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        
        // 移除导航和不必要的元素
        doc.select("nav, .topNav, .bottomNav, .subNav, script, style, footer, .inheritance, .memberSummary").remove();
        
        // 提取主要内容区域
        Elements mainContent = doc.select(".contentContainer, .classDescription, .details, #class-description");
        if (!mainContent.isEmpty()) {
            return mainContent.text();
        }
        
        // 如果无法提取特定区域，返回清理后的body文本
        return doc.body().text();
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
     * 使用LLM分析输入，找出可能的JavaDoc文件路径
     */
    private String findPotentialPaths(String input, String apiRootPath) {
        String prompt = String.format(
                "你是JavaDoc路径查找助手。根据用户的查询，分析最可能包含相关信息的JavaDoc文件路径。\n" +
                "- JDK的JavaDoc目录结构通常按模块和包名组织，例如：\n" +
                "  * java.base/java/lang/String.html 表示java.lang.String类\n" +
                "  * java.base/java/util/package-summary.html 表示java.util包的概述\n" +
                "- 某些文档可能在class-use子目录下，记录了API的使用情况\n" +
                "- 如果用户提供了具体类名，请直接给出类文件路径\n" +
                "- 如果用户只提供了模糊描述，请推断可能的包和类名\n" +
                "- 请返回多个可能的文件路径，每行一个，按可能性排序\n\n" +
                "API根目录：%s\n" +
                "用户查询: %s\n" +
                "可能的JavaDoc路径:", apiRootPath, input);
        
        return llm.messageCompletion(prompt, 0.0);
    }
    
    /**
     * 用于测试JavaDocSearchTool功能的main方法
     */
    public static void main(String[] args) {
        String javaDocRoot = args.length > 0 ? args[0] : "/path/to/javadoc";
        
        // 创建工具实例
        JavaDocSearchTool tool = new JavaDocSearchTool(javaDocRoot);
        
        // 测试场景1: 搜索具体类
        System.out.println("=== 测试具体类搜索 ===");
        String classQuery = "String类的substring方法";
        System.out.println("查询: " + classQuery);
        ToolResponse<String> classResponse = tool.execute(classQuery);
        System.out.println("搜索结果: " + (classResponse.isSuccess() ? "成功" : "失败"));
        if (classResponse.isSuccess()) {
            String result = classResponse.getResult();
            // 限制输出长度，避免控制台输出过多
            if (result.length() > 500) {
                System.out.println(result.substring(0, 500) + "...(更多内容省略)");
            } else {
                System.out.println(result);
            }
        } else {
            System.out.println(classResponse.getMessage());
        }
        
        // 测试场景2: 搜索包
        System.out.println("\n=== 测试包搜索 ===");
        String packageQuery = "java.util包中的集合类";
        System.out.println("查询: " + packageQuery);
        ToolResponse<String> packageResponse = tool.execute(packageQuery);
        System.out.println("搜索结果: " + (packageResponse.isSuccess() ? "成功" : "失败"));
        if (packageResponse.isSuccess()) {
            String result = packageResponse.getResult();
            if (result.length() > 500) {
                System.out.println(result.substring(0, 500) + "...(更多内容省略)");
            } else {
                System.out.println(result);
            }
        } else {
            System.out.println(packageResponse.getMessage());
        }
    }
} 