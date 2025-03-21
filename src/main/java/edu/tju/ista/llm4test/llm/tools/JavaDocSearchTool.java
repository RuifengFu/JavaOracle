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
            // 使用LLM找出可能的类路径
            String potentialPaths = findPotentialPaths(input);
            
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
                        File docFile = new File(javadocBasePath, path);
                        if (docFile.exists()) {
                            // 使用ApiDocProcessor处理文档
                            String content = docProcessor.processApiDocs(docFile);
                            docContent.append("--- 来自文件: ").append(path).append(" ---\n");
                            docContent.append(content).append("\n\n");
                        }
                    } 
                    // 可能是包目录
                    else {
                        Path dirPath = Paths.get(javadocBasePath, path);
                        if (Files.isDirectory(dirPath)) {
                            List<Path> htmlFiles = Files.list(dirPath)
                                    .filter(p -> p.toString().endsWith(".html"))
                                    .collect(Collectors.toList());
                            
                            for (Path htmlFile : htmlFiles) {
                                String content = docProcessor.processApiDocs(htmlFile.toFile());
                                docContent.append("--- 来自目录文件: ").append(htmlFile).append(" ---\n");
                                docContent.append(content).append("\n\n");
                                
                                // 限制返回的文档量
                                if (docContent.length() > 10000) {
                                    docContent.append("...文档内容过多，已截断");
                                    break;
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
     * 使用LLM分析输入，找出可能的JavaDoc文件路径
     */
    private String findPotentialPaths(String input) {
        String prompt = String.format(
                "你是JavaDoc路径查找助手。根据用户的查询，分析最可能包含相关信息的JavaDoc文件路径。\n" +
                "- JDK的JavaDoc目录结构通常按包名组织，如java/lang/String.html表示java.lang.String类\n" +
                "- 如果用户提供了具体类名，请直接给出类文件路径\n" +
                "- 如果用户只提供了模糊描述，请推断可能的包和类名\n" +
                "- 请只返回文件路径，每行一个，不要解释\n\n" +
                "用户查询: %s\n" +
                "可能的JavaDoc路径:", input);
        
        return llm.messageCompletion(prompt, 0.0);
    }
} 