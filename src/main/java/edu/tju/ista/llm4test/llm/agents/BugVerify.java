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

public class BugVerify extends Agent {
    // 基础提示模板

    // 可用的工具
    private final JavaDocSearchTool javadocTool;
    private final SourceCodeSearchTool sourceTool;
    private final WebContentExtractor webTool;
    private final BingSearch searchTool;
    private final JavaExecuteTool executeTool;
    private final JtregExecuteTool jtregTool;
    
    // 新增的信息收集Agent
    private final InformationCollectionAgent infoCollectionAgent;
    
    // 新增的智能工具
    private final ContentProcessor contentProcessor;

    
    // Agent for test case minimization
    private final TestCaseAgent minimizationAgent;
    
    // LLM实例
    private final OpenAI llm;
    private final OpenAI llm_json;

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
    private List<String> hypotheses = new ArrayList<>();
    private Map<String, TestResult> verificationResults = new HashMap<>();
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
        this.llm_json = OpenAI.V3;
        this.javadocTool = new JavaDocSearchTool(javadocPath);
        this.sourceTool = new SourceCodeSearchTool(sourcePath);
        this.webTool = new WebContentExtractor(true);
        this.searchTool = new BingSearch();
        this.executeTool = new JavaExecuteTool();
        this.jtregTool = new JtregExecuteTool();
        this.contentProcessor = new ContentProcessor(bugReportPath + "/content_cache");
        this.minimizationAgent = new TestCaseAgent();
        this.infoCollectionAgent = new InformationCollectionAgent(sourcePath, javadocPath);
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
        
        // 3. 形成假设
        formHypotheses();
        LoggerUtil.logExec(Level.INFO, "形成 " + hypotheses.size() + " 个假设");

        
        // 4. 验证假设
        verifyHypotheses();
        LoggerUtil.logExec(Level.INFO, "验证完成，结果数: " + verificationResults.size());
        
        // 保存验证结果
        saveVerificationResults();
        
        // 5. 形成结论和报告
        String report = generateReport();
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
            
            // 将收集到的信息转换为原有格式并存储
            collectedInfo.clear();
            infoSourceMap.clear();
            infoCounter = 0;
            
            for (InformationCollectionAgent.CollectedInfo info : collectedInfos) {
                String infoId = "INFO_" + (++infoCounter);
                infoSourceMap.put(infoId, info.source);
                
                // 格式化信息内容
                StringBuilder formattedContent = new StringBuilder();
                formattedContent.append("[").append(infoId).append(" 来源: ").append(info.source).append("]\n");
                formattedContent.append("相关性得分: ").append(String.format("%.2f", info.relevanceScore)).append("\n");
                formattedContent.append("信息类型: ").append(info.type).append("\n");
                formattedContent.append("内容大小: ").append(info.content.length()).append(" 字符\n\n");
                formattedContent.append(info.content);
                
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
                
                // 截断过长的API信息
                if (apiInfoWithSource.length() > 8000) {
                    formattedApiInfo.append(apiInfoWithSource.substring(0, 8000)).append("...(API信息已截断)");
                } else {
                    formattedApiInfo.append(apiInfoWithSource);
                }
                
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
     * 假设形成阶段 - 基于收集到的信息形成假设
     */
    private void formHypotheses() {
        // 构建提示，包含所有收集到的信息
        StringBuilder infoBuilder = new StringBuilder();
//        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
//            if (entry.getValue() instanceof String) {
//                String content = (String) entry.getValue();
//                // 限制每项内容的长度，避免提示过长
//                if (content.length() > 30000) {
//                    content = content.substring(0, 30000) + "...(内容已截断)";
//                }
//                if (infoBuilder.length() + content.length() > 100000) {
//                    break;
//                }
//                infoBuilder.append("<").append(entry.getKey()).append(">\n");
//                infoBuilder.append(content).append("\n");
//                infoBuilder.append("</").append(entry.getKey()).append(">\n\n");
//            }
//        }
        
        try {
            String prompt = PromptGen.generateBugVerifyFormHypothesesPrompt(testCode, testOutput, infoBuilder.toString());
            String response = llm.messageCompletion(prompt, 0.7, true);
            response = filterThinkingChain(response);

            hypotheses = extractJsonObjectArrayFromField(response, "hypotheses");
            
            // 立即保存假设
            saveHypotheses(response);
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "生成假设形成prompt失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 保存假设
     */
    private void saveHypotheses(String response) {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建假设目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path hypothesesDir = verifyContextPath.resolve("hypotheses");
            Files.createDirectories(hypothesesDir);

            saveToFile(hypothesesDir.resolve("response.txt").toString(), response);
            
            // 保存每个假设
            for (int i = 0; i < hypotheses.size(); i++) {
                String hypothesis = hypotheses.get(i);
                String id = extractJsonFieldValue(hypothesis, "id");
                if (id == null) id = "H" + (i + 1);
                
                saveToFile(hypothesesDir.resolve(id + ".json").toString(), hypothesis);
                

            }


            // 保存假设汇总
            StringBuilder summary = new StringBuilder();
            summary.append("# 假设汇总\n\n");
            for (String hypothesis : hypotheses) {
                String id = extractJsonFieldValue(hypothesis, "id");
                String description = extractJsonFieldValue(hypothesis, "description");
                String category = extractJsonFieldValue(hypothesis, "category");
                
                summary.append("## ").append(id != null ? id : "未知ID").append("\n\n");
                summary.append("- 描述: ").append(description != null ? description : "无描述").append("\n");
                summary.append("- 类别: ").append(category != null ? category : "未分类").append("\n\n");
            }
            saveToFile(hypothesesDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存假设失败: " + e.getMessage());
        }
    }
    
    /**
     * 假设验证阶段 - 通过执行测试来验证假设
     */
    private void verifyHypotheses() {
        for (String hypothesisJson : hypotheses) {


            String verificationCode = extractJsonFieldValue(hypothesisJson, "verificationCode");
            String hypothesisId = extractJsonFieldValue(hypothesisJson, "id");
            try {
                String prompt = PromptGen.generateBugVerifyInstantiateTestCase(testCode, hypothesisJson);
                String text = filterThinkingChain(llm.messageCompletion(prompt));
                ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
                String code = codeBlocks.isEmpty()? "" : codeBlocks.get(codeBlocks.size() - 1);
                if (code.isEmpty()) {
                    LoggerUtil.logExec(Level.WARNING, "测试用例为空跳过 " + testCaseName + " " + hypothesisId);
                    continue;
                }
                Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
                Path hypothesesDir = verifyContextPath.resolve("hypotheses");
                saveToFile(hypothesesDir.resolve(hypothesisId + ".java").toString(), code);
                verificationCode = code; // 使用实例化后的代码
            } catch (Exception e) {
                LoggerUtil.logExec(Level.SEVERE, "测试用例模板实例化失败 " + testCaseName + " " + hypothesisId + " " + e.getMessage());
            }
            
            if (verificationCode != null && !verificationCode.isEmpty()) {
                if (verificationCode.contains("@test")) { // 包含jtreg风格的注释。
                    ToolResponse<TestResult> result = jtregTool.execute(verificationCode);
                    verificationResults.put(hypothesisId, result.getResult());
                } else {
                    String className = extractClassNameFromCode(verificationCode);
                    if (className != null) {
                        ToolResponse<String> javaResult = executeTool.execute(className);
                        // 创建一个简单的TestResult对象记录执行结果
                        TestResult testResult = new TestResult();
                        testResult.setSuccess(javaResult.isSuccess());
                        testResult.setOutput(javaResult.isSuccess() ? javaResult.getResult() : javaResult.getFailMessage());
                        verificationResults.put(hypothesisId, testResult);
                    }
                }
            }
        }
        
        // 立即保存验证结果
        saveVerificationResults();
    }
    
    /**
     * 保存验证结果
     */
    private void saveVerificationResults() {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建验证结果目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path resultsDir = verifyContextPath.resolve("verification_results");
            Files.createDirectories(resultsDir);
            
            // 保存每个验证结果
            for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
                String id = entry.getKey();
                TestResult result = entry.getValue();
                
                StringBuilder content = new StringBuilder();
                content.append("# 验证结果: ").append(id).append("\n\n");
                content.append("成功: ").append(result.isSuccess()).append("\n\n");
                content.append("输出:\n```\n").append(result.getOutput()).append("\n```\n");
                
                saveToFile(resultsDir.resolve(id + "_result.md").toString(), content.toString());
            }
            
            // 保存验证结果汇总
            StringBuilder summary = new StringBuilder();
            summary.append("# 验证结果汇总\n\n");
            for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
                summary.append("## ").append(entry.getKey()).append("\n\n");
                summary.append("- 成功: ").append(entry.getValue().isSuccess()).append("\n");
                summary.append("- 输出摘要: ").append(
                        entry.getValue().getOutput().length() > 100 
                        ? entry.getValue().getOutput().substring(0, 100) + "..." 
                        : entry.getValue().getOutput()
                ).append("\n\n");
            }
            saveToFile(resultsDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存验证结果失败: " + e.getMessage());
        }
    }
    
    /**
     * 结论形成阶段 - 生成最终报告
     */
    private String generateReport() {
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
        
        // 构建信息源映射，添加API信息和源码
        StringBuilder infoSourceBuilder = new StringBuilder();
        infoSourceBuilder.append("# 信息源映射\n\n");
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

            var manager = ConcurrentExecutionManager.getInstance();
            var futures = new ArrayList<CompletableFuture<Void>>();
            // 为每个bug生成报告
            for (Map.Entry<String, String> entry : verifiedBugs.entrySet()) {
                String originFilePath = entry.getKey();
                String filePath = originFilePath.replace("jdk17u-dev/test", "test");
                String verifyMessage = entry.getValue();
                var future = manager.submitTestTask(() -> {
                    BugVerify agent = new BugVerify(javadocPath, sourcePath, bugReportPath);
                    // 从JDK路径转换到测试路径
                    File originFile = new File(filePath);
                    File testFile = new File(filePath.replace("jdk17u-dev/test", "test"));

                    if (!testFile.exists()) {
                        LoggerUtil.logExec(Level.WARNING, "测试文件不存在: " + filePath);
                        return;
                    }

                    // 使用存在的文件


                    TestCase testcase = new TestCase(testFile);
                    testcase.setOriginFile(originFile);
                    testcase.verifyMessage = verifyMessage;
                    testcase.setApiDocProcessor(ApiInfoProcessor.fromConfig());


                    String testCaseName = testFile.getName().replace(".java", "");
                    LoggerUtil.logExec(Level.INFO, "正在分析bug: " + testCaseName);

                    try {
                        // 读取测试用例内容
                        String sourceCode = Files.readString(testFile.toPath());

                        // 运行测试获取输出
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
                        LoggerUtil.logExec(Level.WARNING, "为测试用例生成报告失败: " + testCaseName);
                        e.printStackTrace();
                    } finally {
                        agent.close();
                    }
                });

                futures.add(future);
            }
            futures.forEach(CompletableFuture::join);
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
        webTool.close();
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


}
