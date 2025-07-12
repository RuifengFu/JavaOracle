package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.prompt.PromptGen;
import freemarker.template.TemplateException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static edu.tju.ista.llm4test.utils.FileUtils.saveToFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BugVerify extends Agent {

    // 可用的工具
    private final JtregExecuteTool jtregTool;
    
    // 新增的信息收集Agent
    private final InformationCollectionAgent infoCollectionAgent;
    // Agent for test case minimization
    private final TestCaseAgent minimizationAgent;
    
    // Agent for hypothesis formation and verification
    private final HypothesisAgent hypothesisAgent;
    
    // LLM实例
    private final OpenAI llm;

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
        this.testCode = testCase.getSourceCode();
        this.testOutput = testCase.getResult().getOutput();
        this.initialAnalysis = testCase.verifyMessage;

        // 创建验证上下文文件夹
        createVerifyContextFolder();
    }

    // 分析状态
    private TestCase testCase;
    private String testCode;
    private String testOutput;
    private String initialAnalysis;
    private Map<String, Object> collectedInfo = new HashMap<>();
    private String conclusion;
    
    // 报告输出路径
    private String bugReportPath = "BugReport";
    private String verifyContextFolder = null;
    private String testCaseName = null;
    
    // 信息源标记
    private int infoCounter = 0;
    private Map<String, String> infoSourceMap = new HashMap<>();
    
    /**
     * 创建BugVerifyAgent
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     */
    public BugVerify(String javadocPath, String sourcePath) {
        this.llm = OpenAI.R1;
        this.jtregTool = new JtregExecuteTool();
        this.minimizationAgent = new TestCaseAgent();
        this.infoCollectionAgent = new InformationCollectionAgent(sourcePath, javadocPath);
        this.hypothesisAgent = new HypothesisAgent();
    }
    
    /**
     * 创建BugVerifyAgent并指定BugReport路径
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     * @param bugReportPath BugReport路径
     */
    public BugVerify(String javadocPath, String sourcePath, String bugReportPath) {
        this(javadocPath, sourcePath);
        if (bugReportPath != null && !bugReportPath.isEmpty()) {
            this.bugReportPath = bugReportPath;
        }
    }

    /**
     * 创建验证上下文文件夹
     */
    private void createVerifyContextFolder() {
        try {
            // 提取测试类名作为文件夹名称
            testCaseName = extractClassNameFromCode(testCode);
            if (testCaseName == null) {
                testCaseName = "UnknownTest";
            }
            
            // 创建以时间戳为后缀的验证上下文文件夹
            String timestamp = String.valueOf(System.currentTimeMillis());
            verifyContextFolder = "VerifyContext_" + timestamp;
            
            // 创建完整路径
            Path testCasePath = Paths.get(bugReportPath, testCaseName);
            Path verifyContextPath = testCasePath.resolve(verifyContextFolder);
            Files.createDirectories(verifyContextPath);
            
            // 保存测试用例和输出
            saveToFile(testCasePath.resolve(testCaseName + ".java").toString(), testCode);
            saveToFile(verifyContextPath.resolve("output.txt").toString(), testOutput);
            if (initialAnalysis != null) {
                saveToFile(verifyContextPath.resolve("initial_analysis.txt").toString(), initialAnalysis);
            }
            
            LoggerUtil.logExec(Level.INFO, "创建验证上下文文件夹: " + verifyContextPath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "创建验证上下文文件夹失败: " + e.getMessage());
        }
    }
    

    /**
     * 执行完整的Bug验证流程
     */
    public String analyze() {
        LoggerUtil.logExec(Level.INFO, "开始Bug验证流程");

        Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);

        // 0. TestCase Reproduce & reduce
        if (this.testCase != null && this.testCase.getResult() != null && this.testCase.getResult().isFail()) {
            LoggerUtil.logExec(Level.INFO, "Verified failure detected. Starting test case minimization for: " + testCase.name);
            try {
                TestCase minimizedCase = minimizationAgent.run(this.testCase, verifyContextPath);
                String minimizedCode = minimizedCase.getSourceCode();
                if (minimizedCode != null && !minimizedCode.equals(this.testCase.getSourceCode())) {
                    // Save the minimized code to a new file
                    String originalFileName = this.testCase.getFile().getName();
                    String minimizedFileName = originalFileName.replace(".java", "_minimized.java");
                    Path minimizedFilePath = verifyContextPath.resolve(minimizedFileName);

                    Files.writeString(minimizedFilePath, minimizedCode);
                    File minimizedFile = minimizedFilePath.toFile();

                    LoggerUtil.logExec(Level.INFO, "Minimization successful. New test case at: " + minimizedFile.getAbsolutePath());
                    // Update the agent's state to use the minimized test case for subsequent steps.
                    this.testCase.setFile(minimizedFile);
                    this.testCode = minimizedCode;
                    this.testOutput = minimizedCase.getResult().getOutput();
                    LoggerUtil.logExec(Level.INFO, "BugVerifyAgent will now proceed with the minimized test case.");
                } else {
                    LoggerUtil.logExec(Level.WARNING, "Minimization process did not reduce the test case. Continuing with the original test case.");
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.SEVERE, "An exception occurred during test case minimization. Continuing with the original test case. " + e);
            }
        }

        // 1. 初始分析
        String initialInsight = performInitialAnalysis();
        saveToFile(verifyContextPath.resolve("initial_insight.json").toString(), initialInsight);

        LoggerUtil.logExec(Level.INFO, "初始分析完成：" + initialInsight);
        
        // 保存初始分析结果



        
        // 2. 收集信息
        collectRelevantInformation(initialInsight);
        LoggerUtil.logExec(Level.INFO, "信息收集完成，共 " + collectedInfo.size() + " 项");
        
        // 保存收集到的信息
        saveCollectedInfo();
        
        // 3. 设置HypothesisAgent工作环境
        hypothesisAgent.setWorkingEnvironment(verifyContextPath.toString(), testCaseName);
        
        // 4. 形成假设
        List<String> hypotheses = hypothesisAgent.formHypotheses(testCode, testOutput, 
            buildCollectedInformationString());
        LoggerUtil.logExec(Level.INFO, "形成 " + hypotheses.size() + " 个假设");
        
        // 5. 验证假设
        Map<String, TestResult> verificationResults = hypothesisAgent.verifyHypotheses(testCode);
        LoggerUtil.logExec(Level.INFO, "验证完成，结果数: " + verificationResults.size());
        
        // 6. 形成结论和报告
        String report = generateReport(hypotheses, verificationResults);
        LoggerUtil.logExec(Level.INFO, "Bug验证报告已生成");
        
        // 保存最终报告
        if (testCaseName != null) {
            String fileName = "TestErrorAnalysis.md";
            if (report.contains("BUG REPORT")) {
                fileName = "BugReport.md";
                if (report.contains("TESTCASE ERROR ANALYSIS")) {
                    fileName = "BugReportWithError.md";
                }
            } else if (fileName.contains("TESTCASE ERROR ANALYSIS")) {
                fileName = "TestCaseErrorAnalysis.md";
            } else {
                fileName = "WrongFormatReport.md";
            }
            saveToFile(Paths.get(bugReportPath, testCaseName, fileName).toString(), report);
            
            // 记录最终结果
            var result = fileName.replace(".md", "");
            LoggerUtil.logResult(Level.INFO, "BugVerifyAgent: " + testCase.getFile().getAbsolutePath() + " " + result);
            LoggerUtil.logExec(Level.INFO, "BugVerifyAgent: " + testCase.getFile().getAbsolutePath() + " " + result);
        }
        
        return report;
    }
    
    /**
     * 过滤LLM响应中的思维链标签
     * @param response LLM响应内容
     * @return 过滤后的内容
     */
    private static String filterThinkingChain(String response) {
        if (response == null) return null;
        // 移除<think>...</think>标签及其内容
        String filtered = response.replaceAll("<thinking>[\\s\\S]*?</thinking>", "");
        LoggerUtil.logExec(Level.INFO, "过滤后的内容\n" + filtered.trim());
        // 返回清理后的响应
        return filtered.trim();
    }

    /*
        * 过滤JSON标记
        * @param response LLM响应内容
        * 去掉里面的 ```json 和 ``` 标签
     */
    //    private static String filiterJsonMark(String response) {
    //        if (response == null) return null;
    //        var filtered = response.replaceFirst("^```(json)?", "").replaceFirst("```\\s*$", "");
    //        return filtered;
    //    }
    
    /**
     * 初始分析阶段 - 分析测试用例和输出，确定问题性质
     */
    private String performInitialAnalysis() {
        try {
            String prompt = PromptGen.generateBugVerifyInitialAnalysisPrompt(testCode, testOutput, initialAnalysis);
            String response = llm.messageCompletion(prompt, 0.7, true);
            return filterThinkingChain(response);
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "生成初始分析prompt失败: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }
    
    /**
     * 信息收集阶段 - 使用信息收集Agent收集相关信息
     */
    private void collectRelevantInformation(String initialInsight) {
        LoggerUtil.logExec(Level.INFO, "开始信息收集阶段");
        
        try {
            // 获取测试用例的API信息和源码
            String apiInfoWithSource = "";
            if (testCase != null) {
                apiInfoWithSource = testCase.getApiInfoWithSource();
                LoggerUtil.logExec(Level.INFO, "获取到API信息和源码，大小: " + apiInfoWithSource.length() + " 字符");
            }
            
            // 使用信息收集Agent收集相关信息
            List<InformationCollectionAgent.CollectedInfo> collectedInfos = infoCollectionAgent.collectInformation(
                initialInsight, 
                testCode, 
                testOutput, 
                apiInfoWithSource
            );
            
            LoggerUtil.logExec(Level.INFO, String.format("信息收集完成，共收集到 %d 条信息", collectedInfos.size()));
            
            // 获取完整的详细报告
            String detailedReport = infoCollectionAgent.getDetailedReport();
            LoggerUtil.logExec(Level.INFO, "获取到完整信息源报告，大小: " + detailedReport.length() + " 字符");
            
            // 保存完整的详细报告到文件
            saveDetailedInfoReport(detailedReport);
            
            // 将收集到的信息转换为原有格式并存储
            collectedInfo.clear();
            infoSourceMap.clear();
            infoCounter = 0;
            
            for (InformationCollectionAgent.CollectedInfo info : collectedInfos) {
                String infoId = "INFO_" + (++infoCounter);
                infoSourceMap.put(infoId, info.source);
                
                // 格式化信息内容，包含完整内容
                StringBuilder formattedContent = new StringBuilder();
                formattedContent.append("[").append(infoId).append(" 来源: ").append(info.source).append("]\n");
                formattedContent.append("相关性得分: ").append(String.format("%.2f", info.relevanceScore)).append("\n");
                formattedContent.append("信息类型: ").append(info.type).append("\n");
                formattedContent.append("内容大小: ").append(info.content.length()).append(" 字符\n\n");
                formattedContent.append("=== 完整内容 ===\n");
                formattedContent.append(info.content);
                formattedContent.append("\n=== 内容结束 ===\n");
                
                collectedInfo.put(info.id, formattedContent.toString());
                
                LoggerUtil.logExec(Level.INFO, String.format("收集信息: %s (相关性: %.2f, 大小: %d)", 
                    info.source, info.relevanceScore, info.content.length()));
            }
            
            // 添加API信息和源码到收集的信息中（如果有的话）
            if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
                String infoId = "INFO_" + (++infoCounter);
                infoSourceMap.put(infoId, "测试用例API信息和源码");
                
                StringBuilder formattedApiInfo = new StringBuilder();
                formattedApiInfo.append("[").append(infoId).append(" 来源: 测试用例API信息和源码]\n");
                formattedApiInfo.append("信息类型: API_INFO_WITH_SOURCE\n");
                formattedApiInfo.append("内容大小: ").append(apiInfoWithSource.length()).append(" 字符\n\n");
                formattedApiInfo.append("=== 完整API信息 ===\n");
                
                // 截断过长的API信息
                if (apiInfoWithSource.length() > 8000) {
                    formattedApiInfo.append(apiInfoWithSource.substring(0, 8000)).append("...(API信息已截断)");
                } else {
                    formattedApiInfo.append(apiInfoWithSource);
                }
                formattedApiInfo.append("\n=== API信息结束 ===\n");
                
                collectedInfo.put("api_info_with_source", formattedApiInfo.toString());
                
                LoggerUtil.logExec(Level.INFO, String.format("添加API信息: 大小 %d 字符", apiInfoWithSource.length()));
            }
            
            // 计算总的信息大小
            int totalSize = collectedInfo.values().stream()
                .mapToInt(content -> content instanceof String ? ((String) content).length() : 0)
                .sum();
            
            LoggerUtil.logExec(Level.INFO, String.format("信息收集完成，总计 %d 条信息，总大小: %d 字符", 
                collectedInfo.size(), totalSize));
            
            // 如果总大小超过限制，记录警告
            if (totalSize > 32000) {
                LoggerUtil.logExec(Level.WARNING, String.format("收集的信息总大小 (%d) 超过建议限制 (32000)", totalSize));
            }
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "信息收集过程中出现错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 保存完整的信息收集详细报告
     */
    private void saveDetailedInfoReport(String detailedReport) {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path infoDir = verifyContextPath.resolve("collected_info");
            Files.createDirectories(infoDir);
            
            // 保存完整的详细报告
            saveToFile(infoDir.resolve("detailed_info_report.md").toString(), detailedReport);
            
            LoggerUtil.logExec(Level.INFO, "已保存完整信息收集报告: detailed_info_report.md");
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存详细信息报告失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存收集到的信息
     */
    private void saveCollectedInfo() {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建info子目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path infoDir = verifyContextPath.resolve("collected_info");
            Files.createDirectories(infoDir);
            
            // 保存每项收集到的信息
            for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String fileName = entry.getKey().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                    saveToFile(infoDir.resolve(fileName).toString(), (String) entry.getValue());
                }
            }
            
            // 保存汇总信息
            StringBuilder summary = new StringBuilder();
            summary.append("# 收集的信息汇总\n\n");
            for (String key : collectedInfo.keySet()) {
                summary.append("- ").append(key).append("\n");
            }
            
            // 添加信息源映射
            summary.append("\n## 信息源映射\n\n");
            for (Map.Entry<String, String> entry : infoSourceMap.entrySet()) {
                summary.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            
            saveToFile(infoDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存收集的信息失败: " + e.getMessage());
        }
    }
    

    

    

    

    
    /**
     * 构建收集信息的字符串
     */
    private String buildCollectedInformationString() {
        StringBuilder infoBuilder = new StringBuilder();
        infoBuilder.append("# 收集的信息\n\n");
        
        // 添加所有收集到的信息的完整内容
        int infoCount = 0;
        int totalContentSize = 0;
        final int MAX_CONTENT_SIZE = 80000; // 限制总内容大小，避免prompt过长
        
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                String content = (String) entry.getValue();
                
                // 检查是否会超出大小限制
                if (totalContentSize + content.length() > MAX_CONTENT_SIZE) {
                    LoggerUtil.logExec(Level.WARNING, "信息内容过多，已截断部分内容以避免prompt过长");
                    break;
                }
                
                infoBuilder.append("## 信息源 ").append(++infoCount).append(": ").append(entry.getKey()).append("\n\n");
                infoBuilder.append(content).append("\n\n");
                infoBuilder.append("---\n\n");
                
                totalContentSize += content.length();
            }
        }
        
        LoggerUtil.logExec(Level.INFO, String.format("信息字符串构建完成，使用了 %d 条信息，总大小: %d 字符", 
            infoCount, totalContentSize));
        
        return infoBuilder.toString();
    }
    
    /**
     * 结论形成阶段 - 生成最终报告
     */
    private String generateReport(List<String> hypotheses, Map<String, TestResult> verificationResults) {
        // 构建验证结果
        StringBuilder resultsBuilder = new StringBuilder();
        for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
            resultsBuilder.append("<").append(entry.getKey()).append("_Result>\n");
            TestResult result = entry.getValue();
            resultsBuilder.append("成功: ").append(result.isSuccess()).append("\n");
            resultsBuilder.append("输出:\n").append(result.getOutput()).append("\n");
            resultsBuilder.append("</").append(entry.getKey()).append("_Result>\n\n");
        }
        
        // 构建假设信息
        StringBuilder hypothesesBuilder = new StringBuilder();
        for (String hypothesis : hypotheses) {
            hypothesesBuilder.append(hypothesis).append("\n\n");
        }
        
        // 检查是否有测试用例问题相关的假设被验证
        boolean hasTestCaseIssueHypothesis = false;
        String testIssueHypothesisId = "";
        
        for (String hypothesisJson : hypotheses) {
            String category = extractJsonFieldValue(hypothesisJson, "category");
            String hypothesisId = extractJsonFieldValue(hypothesisJson, "id");
            
            if (category != null && (
                    category.equals("TEST_ERROR") || 
                    category.equals("SPEC_VIOLATION") || 
                    category.equals("UNDEFINED_BEHAVIOR"))) {
                
                // 检查验证结果是否支持这个假设
                TestResult result = verificationResults.get(hypothesisId);
                if (result != null && result.isSuccess()) {
                    hasTestCaseIssueHypothesis = true;
                    testIssueHypothesisId = hypothesisId;
                    break;
                }
            }
        }
        
        // 构建增强的信息源内容，包含分析过程
        StringBuilder infoSourceBuilder = new StringBuilder();
        infoSourceBuilder.append("# 信息收集和分析过程\n\n");
        
        // 添加信息收集统计
        Map<InformationCollectionAgent.InfoType, Integer> typeStats = new HashMap<>();
        Map<InformationCollectionAgent.InfoType, Integer> typeSizes = new HashMap<>();
        
        // 统计各类型信息
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                String content = (String) entry.getValue();
                // 从内容中提取信息类型
                InformationCollectionAgent.InfoType type = extractInfoType(content);
                typeStats.merge(type, 1, Integer::sum);
                typeSizes.merge(type, content.length(), Integer::sum);
            }
        }
        
        infoSourceBuilder.append("## 信息收集统计\n\n");
        infoSourceBuilder.append("总计收集: ").append(collectedInfo.size()).append(" 条信息\n\n");
        
        for (InformationCollectionAgent.InfoType type : InformationCollectionAgent.InfoType.values()) {
            int count = typeStats.getOrDefault(type, 0);
            int size = typeSizes.getOrDefault(type, 0);
            if (count > 0) {
                infoSourceBuilder.append("- **").append(type).append("**: ")
                    .append(count).append(" 条，共 ").append(size).append(" 字符\n");
            }
        }
        infoSourceBuilder.append("\n");
        
        // 添加信息质量评估
        infoSourceBuilder.append("## 信息质量评估\n\n");
        
        // 从InformationCollectionAgent获取详细报告
        if (infoCollectionAgent != null) {
            String detailedReport = infoCollectionAgent.getDetailedReport();
            if (detailedReport != null && !detailedReport.isEmpty()) {
                infoSourceBuilder.append("### 完整信息收集报告\n\n");
                infoSourceBuilder.append(detailedReport).append("\n\n");
            }
        }
        
        infoSourceBuilder.append("## 关键信息摘要\n\n");
        
        // 按重要性排序显示前5条最重要的信息
        List<Map.Entry<String, Object>> sortedInfo = collectedInfo.entrySet().stream()
            .filter(entry -> entry.getValue() instanceof String)
            .sorted((e1, e2) -> {
                // 简单的重要性排序：源码 > API文档 > 网络搜索
                String content1 = (String) e1.getValue();
                String content2 = (String) e2.getValue();
                InformationCollectionAgent.InfoType type1 = extractInfoType(content1);
                InformationCollectionAgent.InfoType type2 = extractInfoType(content2);
                return Integer.compare(getTypeWeight(type2), getTypeWeight(type1));
            })
            .limit(5)
            .collect(Collectors.toList());
        
        for (int i = 0; i < sortedInfo.size(); i++) {
            Map.Entry<String, Object> entry = sortedInfo.get(i);
            String content = (String) entry.getValue();
            InformationCollectionAgent.InfoType type = extractInfoType(content);
            
            infoSourceBuilder.append("### 关键信息 ").append(i + 1).append(" (").append(type).append(")\n\n");
            infoSourceBuilder.append("来源: ").append(entry.getKey()).append("\n\n");
            
            // 提取内容预览（前500字符）
            String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;
            infoSourceBuilder.append("内容预览:\n```\n").append(preview).append("\n```\n\n");
        }
        
        // 添加完整的收集信息（用于LLM分析）
        infoSourceBuilder.append("# 完整信息源内容（用于分析）\n\n");
        
        int sourceCount = 0;
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                sourceCount++;
                infoSourceBuilder.append("## 信息源 ").append(sourceCount).append(": ").append(entry.getKey()).append("\n\n");
                infoSourceBuilder.append((String) entry.getValue()).append("\n\n");
                infoSourceBuilder.append("---\n\n");
            }
        }
        
        // 添加信息源映射摘要
        infoSourceBuilder.append("# 信息源映射摘要\n\n");
        for (Map.Entry<String, String> entry : infoSourceMap.entrySet()) {
            infoSourceBuilder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        // 添加测试用例的API文档信息
        if (testCase != null) {
            infoSourceBuilder.append("\n# 测试用例API信息和源码\n\n");
            String apiInfoWithSource = testCase.getApiInfoWithSource();
            if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
                infoSourceBuilder.append(apiInfoWithSource).append("\n");
            } else {
                infoSourceBuilder.append("无API信息和源码数据\n");
            }
        }
        
        LoggerUtil.logExec(Level.INFO, String.format("最终报告包含 %d 个信息源，信息源内容大小: %d 字符", 
            sourceCount, infoSourceBuilder.length()));
        
        // 根据是否为测试用例问题调整提示模板
        try {
            String prompt;
            String promptType;
            if (hasTestCaseIssueHypothesis) {
                prompt = PromptGen.generateBugVerifyTestCaseReportPrompt(
                    testCode, testOutput, hypothesesBuilder.toString(), 
                    resultsBuilder.toString(), infoSourceBuilder.toString());
                promptType = "test_case_report";
            } else {
                prompt = PromptGen.generateBugVerifyBugReportPrompt(
                    testCode, testOutput, hypothesesBuilder.toString(), 
                    resultsBuilder.toString(), infoSourceBuilder.toString());
                promptType = "bug_report";
            }
            
            // 保存完整的prompt到文件
            savePromptToFile(prompt, promptType, hasTestCaseIssueHypothesis, testIssueHypothesisId);
            
            conclusion = llm.messageCompletion(prompt);
            return conclusion;
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "生成报告prompt失败: " + e.getMessage());
            e.printStackTrace();
            return "生成报告失败: " + e.getMessage();
        }
    }
    
    /**
     * 保存生成报告的完整prompt到文件
     */
    private void savePromptToFile(String prompt, String promptType, boolean hasTestCaseIssue, String testIssueHypothesisId) {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path promptsDir = verifyContextPath.resolve("prompts");
            Files.createDirectories(promptsDir);
            
            // 直接保存prompt内容
            String promptFileName = "generate_report_" + promptType + "_prompt.txt";
            saveToFile(promptsDir.resolve(promptFileName).toString(), prompt);
            
            LoggerUtil.logExec(Level.INFO, "已保存生成报告的prompt: " + promptFileName);
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存prompt失败: " + e.getMessage());
        }
    }
    
    /**
     * 从JSON字符串中提取字段值
     */
    private String extractJsonFieldValue(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode fieldNode = rootNode.path(fieldName);

            if (!fieldNode.isMissingNode()) {
                // 如果是文本节点，直接返回文本值；否则返回节点的字符串表示
                return fieldNode.isValueNode() ? fieldNode.asText() : fieldNode.toString();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "JSON字段提取失败 (" + fieldName + "): " + e.getMessage() + "\nJSON: " + json);
        }
        return null;
    }
    
    /**
     * 从JSON中提取字符串数组
     */
    private List<String> extractJsonArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode arrayNode = rootNode.path(fieldName);

            if (arrayNode.isArray()) {
                for (JsonNode elementNode : arrayNode) {
                    // 提取数组中每个元素的值作为字符串
                    result.add(elementNode.asText());
                }
            } else {
                LoggerUtil.logExec(Level.WARNING, "JSON字段 '" + fieldName + "' 不是一个数组或未找到。\nJSON: " + json);
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "JSON数组提取失败 (" + fieldName + "): " + e.getMessage() + "\nJSON: " + json);
        }
        return result;
    }
    
    /**
     * 从代码中提取类名
     */
    private String extractClassNameFromCode(String code) {
        Pattern pattern = Pattern.compile("public\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    


    /**
     * 从日志文件中提取已验证的bug并生成报告
     * @param logPath 日志文件路径
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     * @param bugReportPath Bug报告输出路径
     */
    public static void verifyBugsFromLog(String logPath, String javadocPath, String sourcePath, String bugReportPath) {
        LoggerUtil.logExec(Level.INFO, "开始从日志文件验证bug: " + logPath);
        
        // 创建BugVerifyAgent
        try {
            // 读取日志文件
            File logFile = new File(logPath);
            if (!logFile.exists()) {
                LoggerUtil.logExec(Level.WARNING, "日志文件不存在: " + logPath);
                return;
            }
            
            List<String> lines = Files.readAllLines(logFile.toPath());
            Map<String, String> verifiedBugs = new HashMap<>();
            
            // 解析日志文件，提取已验证的bug
            for (int i = 0; i < lines.size(); i++) {

                String line = lines.get(i).trim();
                
                // 匹配格式：文件路径 VERIFIED_BUG
                if (line.contains(" VERIFIED_BUG")) {
                    LoggerUtil.logExec(Level.FINE, "找到VERIFIED_BUG行: " + line);
                    
                    // 从行中提取文件路径
                    String[] parts = line.split("\\s+");
                    String filePath = null;
                    
                    // 查找VERIFIED_BUG前的文件路径
                    for (int j = 0; j < parts.length - 1; j++) {
                        if (parts[j + 1].equals("VERIFIED_BUG") && parts[j].contains(".java")) {
                            filePath = parts[j];
                            break;
                        }
                    }
                    
                    if (filePath != null) {
                        // 标准化文件路径
//                        filePath = filePath.replace("jdk17u-dev/test", "test");
                        LoggerUtil.logExec(Level.FINE, "提取到文件路径: " + filePath);
                        
                        // 查找后续几行中的验证消息
                        String verifyMessage = "";
                        for (int k = i + 1; k < Math.min(i + 3, lines.size()); k++) {
                            String nextLine = lines.get(k).trim();
                            if (nextLine.contains("{") && nextLine.contains("}")) {
                                verifyMessage = nextLine;
                                LoggerUtil.logExec(Level.FINE, "找到验证消息: " + verifyMessage);
                                break;
                            }
                        }

                        verifiedBugs.put(filePath, verifyMessage);
                        

                    } else {
                        LoggerUtil.logExec(Level.WARNING, "无法从行中提取文件路径: " + line);
                    }
                }
            }
            
            LoggerUtil.logExec(Level.INFO, "从日志中找到 " + verifiedBugs.size() + " 个已验证的bug");

            // 使用批量处理线程池进行并发处理，避免与测试线程池冲突
            var manager = ConcurrentExecutionManager.getInstance();
            var futures = new ArrayList<CompletableFuture<Void>>();
            
            // 为每个bug并发生成报告
            for (Map.Entry<String, String> entry : verifiedBugs.entrySet()) {
                String originFilePath = entry.getKey();
                String filePath = originFilePath.replace("jdk17u-dev/test", "test");
                String verifyMessage = entry.getValue();
                
                // 使用批量处理线程池，避免占用测试线程池
                CompletableFuture<Void> future = manager.submitTestTask(() -> {
                    LoggerUtil.logExec(Level.INFO, "开始处理bug: " + filePath);
                    
                    BugVerify agent = new BugVerify(javadocPath, sourcePath, bugReportPath);
                    
                    try {
                        // 从JDK路径转换到测试路径
                        File originFile = new File(filePath);
                        File testFile = new File(filePath.replace("jdk17u-dev/test", "test"));

                        if (!testFile.exists()) {
                            LoggerUtil.logExec(Level.WARNING, "测试文件不存在: " + filePath);
                            return null;
                        }

                        // 使用存在的文件
                        TestCase testcase = new TestCase(testFile);
                        testcase.setOriginFile(originFile);
                        testcase.verifyMessage = verifyMessage;
                        testcase.setApiDocProcessor(ApiInfoProcessor.fromConfig());

                        String testCaseName = testFile.getName().replace(".java", "");
                        LoggerUtil.logExec(Level.INFO, "正在分析bug: " + testCaseName);

                        // 读取测试用例内容
                        String sourceCode = Files.readString(testFile.toPath());

                        // 运行测试获取输出 - 现在安全了，因为JtregExecuteTool使用同步调用
                        JtregExecuteTool jtregTool = new JtregExecuteTool();
                        ToolResponse<TestResult> response = jtregTool.execute(testcase.getFile().toPath(), testCaseName);

                        String testOutput;
                        if (response.isSuccess()) {
                            testOutput = response.getResult().getOutput();
                            testcase.setResult(response.getResult());
                        } else {
                            testOutput = "无法获取测试输出";
                            LoggerUtil.logResult(Level.SEVERE, testcase.getFile().getAbsolutePath() + ": 无法获取测试输出");
                        }

                        // 设置测试数据并分析
                        agent.setTestCase(testcase);
                        agent.analyze();

                        LoggerUtil.logExec(Level.INFO, "Bug报告已生成: " + testCaseName);
                        
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "为测试用例生成报告失败: " + filePath + " - " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        // 确保资源得到正确释放
                        agent.close();
                    }
                    
                    return null;
                });

                futures.add(future);
            }
            
            // 等待所有并发任务完成
            LoggerUtil.logExec(Level.INFO, "等待 " + futures.size() + " 个并发Bug验证任务完成...");
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            LoggerUtil.logExec(Level.INFO, "Bug验证和报告生成完成，报告保存在: " + bugReportPath);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Bug验证过程失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void verifyBugFromFile(String filePath, String verifyMessage, String originFilePath) {

        File testFile = new File(filePath);

        if (!testFile.exists()) {
            LoggerUtil.logExec(Level.WARNING, "测试文件不存在: " + filePath);
            return;
        }

        // 使用存在的文件
        TestCase testcase = new TestCase(testFile);
        testcase.setApiDocProcessor(ApiInfoProcessor.fromConfig());

        if (originFilePath != null) {
            File originFile = new File(originFilePath);
            if (originFile.exists()) {
                testcase.setOriginFile(originFile);
            } else {
                LoggerUtil.logExec(Level.WARNING, "原始文件不存在: " + originFilePath);
            }
        }

        String testCaseName = testFile.getName().replace(".java", "");
        LoggerUtil.logExec(Level.INFO, "正在分析bug: " + testCaseName);
        try {
            ToolResponse<TestResult> response = jtregTool.execute(testcase.getFile().toPath(), testCaseName);
            String testOutput;
            if (response.isSuccess()) {
                testOutput = response.getResult().getOutput();
                testcase.setResult(response.getResult());
            } else {
                testOutput = "无法获取测试输出";
                LoggerUtil.logResult(Level.SEVERE, testcase.getFile().getAbsolutePath() + ": 无法获取测试输出");
            }
            if (verifyMessage != null && !verifyMessage.isEmpty()) {
                testcase.verifyMessage = verifyMessage;
            } else {
                testcase.verifyTestFail();
            }
            this.setTestCase(testcase);
            this.analyze();
            LoggerUtil.logExec(Level.INFO, "Bug报告已生成: " + testCaseName);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "为测试用例生成报告失败: " + testCaseName);
            e.printStackTrace();
        } finally {
            this.close();
        }
    }
    
    public void close(){
        if (infoCollectionAgent != null) {
            infoCollectionAgent.close();
        }
    }

    /**
     * 从JSON中提取对象数组
     */
    public static List<String> extractJsonObjectArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        LoggerUtil.logExec(Level.INFO,"Attempting to extract JSON object array for field: " + fieldName + "\nInput JSON preview: " + (json.length() > 100 ? json.substring(0, 100) + "..." : json));
        List<String> result = new ArrayList<>();
        try {
            // ObjectMapper 用于解析最终的 JSON
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json); // 直接解析输入的json字符串
            JsonNode arrayNode = rootNode.path(fieldName);

            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    // 将每个对象节点转换为其字符串表示形式
                    result.add(node.toString().trim());
                }
                LoggerUtil.logExec(Level.INFO, "Successfully extracted " + result.size() + " objects from array field '" + fieldName + "'.");
            } else {
                 LoggerUtil.logExec(Level.WARNING, "Field '" + fieldName + "' is not an array or was not found in the JSON.");
            }
        } catch (IOException e) { // Changed Exception to IOException for specificity
            LoggerUtil.logExec(Level.WARNING, "Failed to parse JSON or extract object array field '" + fieldName + "': " + e.getMessage());
            // 可以在这里添加调用 string2json 的逻辑作为后备，但这会改变方法预期
            // 例如:
            // String cleanedJson = new BugVerifyAgent(null, null).string2json(json); // 需要处理构造函数
            // if (cleanedJson != null) { /* retry parsing cleanedJson */ }
        }
        LoggerUtil.logExec(Level.FINE,"Resulting object strings count: " + result.size()); // Changed level to FINE for less verbose default logging
        return result;
    }

    /**
     * 从信息内容中提取信息类型
     */
    private InformationCollectionAgent.InfoType extractInfoType(String content) {
        if (content.contains("信息类型: SOURCE_CODE") || content.contains("信息类型: 源码")) {
            return InformationCollectionAgent.InfoType.SOURCE_CODE;
        } else if (content.contains("信息类型: JAVADOC") || content.contains("信息类型: API文档")) {
            return InformationCollectionAgent.InfoType.JAVADOC;
        } else if (content.contains("信息类型: WEB_SEARCH") || content.contains("来源: 网页")) {
            return InformationCollectionAgent.InfoType.WEB_SEARCH;
        } else {
            return InformationCollectionAgent.InfoType.SOURCE_CODE; // 默认
        }
    }
    
    /**
     * 获取信息类型的权重（用于排序）
     */
    private int getTypeWeight(InformationCollectionAgent.InfoType type) {
        switch (type) {
            case SOURCE_CODE: return 3;
            case JAVADOC: return 2;
            case WEB_SEARCH: return 1;
            default: return 0;
        }
    }


}
