package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.prompt.PromptGen;
import freemarker.template.TemplateException;
import org.apache.commons.io.FileUtils;
import edu.tju.ista.llm4test.config.GlobalConfig;

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

import java.util.ArrayList;
import java.util.List;
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
    private final OpenAI llm = OpenAI.K2;

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

    private String enhanceVerifyFailureReason = "Unknown";
    
    
    // 报告输出路径
    private String bugReportPath = "BugReport";
    private String verifyContextFolder = null;
    private String testCaseName = null;
    private Path verifyContextPath = null;
    
    // 信息源标记
    private int infoCounter = 0;
    private Map<String, String> infoSourceMap = new HashMap<>();
    
    /**
     * 获取测试用例标识符，用于日志记录
     */
    private String getTestCaseIdentifier() {
        if (testCase != null && testCase.getFile() != null) {
            return testCase.getFile().getName().replace(".java", "");
        } else if (testCaseName != null) {
            return testCaseName;
        } else {
            return "UnknownTestCase";
        }
    }
    
    /**
     * 带测试用例标识的日志记录
     */
    private void logWithTestCase(Level level, String message) {
        String identifier = getTestCaseIdentifier();
        LoggerUtil.logExec(level, String.format("[BugVerify][%s] %s", identifier, message));
    }
    
    /**
     * 带测试用例标识的日志记录（INFO级别）
     */
    private void logWithTestCase(String message) {
        logWithTestCase(Level.INFO, message);
    }
    
    /**
     * 创建BugVerifyAgent
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     */
    public BugVerify(String javadocPath, String sourcePath) {
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
            // 提取类名作为基础名称
            String className = "UnknownTest";
            if (this.testCase != null) {
                className = this.testCase.getName();
            }
            
            this.testCaseName = className;

            // 检查重名，如果重名，则使用父目录名进行区分
            Path bugDir = Paths.get(bugReportPath, "bug");
            Path testcaseDir = Paths.get(bugReportPath, "testcase");

            Path potentialBugPath = bugDir.resolve(this.testCaseName);
            Path potentialTestcasePath = testcaseDir.resolve(this.testCaseName);

            if (Files.exists(potentialBugPath) || Files.exists(potentialTestcasePath)) {
                File testFile = this.testCase.getFile();
                if (testFile != null && testFile.getParentFile() != null) {
                    String parentDirName = testFile.getParentFile().getName();
                    this.testCaseName = parentDirName + "." + className;
                }
            }
            
            // 创建以时间戳为后缀的验证上下文文件夹
            String timestamp = String.valueOf(System.currentTimeMillis());
            verifyContextFolder = "VerifyContext_" + timestamp;
            
            // 创建完整路径 (始终在testcase下)
            Path testCasePath = Paths.get(bugReportPath, "testcase", this.testCaseName);
            this.verifyContextPath = testCasePath.resolve(verifyContextFolder);
            Files.createDirectories(verifyContextPath);
        
            // 保存测试用例和输出
            saveToFile(testCasePath.resolve(this.testCaseName + ".java").toString(), testCode);
            saveToFile(verifyContextPath.resolve("output.txt").toString(), testOutput);
            if (initialAnalysis != null) {
                saveToFile(verifyContextPath.resolve("initial_analysis.txt").toString(), initialAnalysis);
            }
            
            logWithTestCase("创建验证上下文文件夹: " + verifyContextPath);
        } catch (IOException e) {
            logWithTestCase(Level.WARNING, "创建验证上下文文件夹失败: " + e.getMessage());
        }
    }
    

    /**
     * 执行完整的Bug验证流程
     */
    public String analyze() {
        logWithTestCase("开始Bug验证流程");

        Path sourceTestCaseDir = Paths.get(bugReportPath, "testcase", testCaseName);
        Path verifyContextPath = sourceTestCaseDir.resolve(verifyContextFolder);

        // 0. TestCase Reproduce & reduce - 在完整环境中进行约简
        if (this.testCase != null && this.testCase.getResult() != null && this.testCase.getResult().isFail()) {
            logWithTestCase("Verified failure detected. Setting up verify environment for minimization: " + testCase.name);
            
            // 创建verify环境并复制完整测试目录
            Path verifyDir = setupVerifyEnvironment();
            if (verifyDir == null) {
                logWithTestCase(Level.WARNING, "Failed to setup verify environment, skipping minimization");
            } else {
                // 更新测试用例路径到verify目录
                TestCase verifyTestCase = updateTestCaseToVerifyDir(this.testCase, verifyDir);
                
                if (verifyTestCase != null) {
                    logWithTestCase("Starting test case minimization in verify environment for: " + verifyTestCase.name);
                    try {
                        TestCase minimizedCase = minimizationAgent.run(verifyTestCase, verifyDir);

                        // 检查约简是否成功
                        if (minimizedCase != null && minimizedCase.getSourceCode() != null &&
                            !minimizedCase.getSourceCode().equals(verifyTestCase.getSourceCode())) {

                            String minimizedCode = minimizedCase.getSourceCode();
                            // Save the minimized code to the original context path for record keeping
                            String originalFileName = this.testCase.getFile().getName();
                            String minimizedFileName = originalFileName.replace(".java", "_minimized.java");
                            Path minimizedFilePath = verifyContextPath.resolve(minimizedFileName);

                            Files.writeString(minimizedFilePath, minimizedCode);
                            File minimizedFile = minimizedFilePath.toFile();

                            logWithTestCase("Minimization successful in verify environment. Minimized code saved at: " + minimizedFile.getAbsolutePath());
                            
                            // Update the agent's state to use the minimized test case for subsequent steps
                            this.testCase.setFile(minimizedFile);
                            this.testCode = minimizedCode;
                            this.testOutput = minimizedCase.getResult().getOutput();
                            logWithTestCase("BugVerifyAgent will now proceed with the minimized test case from verify environment.");
                        } else {
                            logWithTestCase(Level.WARNING, "Minimization process in verify environment did not reduce the test case. Continuing with the original test case.");
                        }
                    } catch (Exception e) {
                        logWithTestCase(Level.SEVERE, "An exception occurred during test case minimization in verify environment. Continuing with the original test case. " + e);
                    }
                } else {
                    logWithTestCase(Level.WARNING, "Failed to update test case to verify directory, skipping minimization");
                }
            }
        }

        // 1. 初始分析
        String initialInsight = performInitialAnalysis();
        saveToFile(verifyContextPath.resolve("initial_insight.json").toString(), initialInsight);

        logWithTestCase("初始分析完成：" + initialInsight);
        
        // 保存初始分析结果


        
        // 2. 收集信息
        collectRelevantInformation(initialInsight);
        logWithTestCase("信息收集完成，共 " + collectedInfo.size() + " 项");
        
        // 保存收集到的信息
        saveCollectedInfo();
        List<String> hypotheses = new ArrayList<>();
        Map<String, TestResult> verificationResults = new HashMap<>();
        if (edu.tju.ista.llm4test.config.GlobalConfig.isIncludeHypothesis()) {
            // 3. 设置HypothesisAgent工作环境
            hypothesisAgent.setWorkingEnvironment(verifyContextPath.toString(), testCaseName);

            // 4. 形成假设
            hypotheses = hypothesisAgent.formHypotheses(testCode, testOutput,
                    buildCollectedInformationString());
            logWithTestCase("形成 " + hypotheses.size() + " 个假设");

            // 5. 验证假设
            verificationResults = hypothesisAgent.verifyHypotheses(testCode);
            logWithTestCase("验证完成，结果数: " + verificationResults.size());
        }

        // 6. 形成结论和报告
        String reportJson = generateReport(hypotheses, verificationResults);
        logWithTestCase("Bug验证报告已生成");
        
        // 保存最终报告
        if (testCaseName != null) {
            String fileName;
            String reportContent;

            try {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(reportJson);
                String bugType = rootNode.path("bug_type").asText("UNKNOWN");
                reportContent = rootNode.path("report").asText();

                Path targetBugDir = Paths.get(bugReportPath, "bug", testCaseName);

                Path reportDir;

                switch (bugType) {
                    case "JDK_BUG":
                    case "BOTH":
                        fileName = "BugReport.md";
                        if (Files.exists(sourceTestCaseDir)) {
                            try {
                                Files.move(sourceTestCaseDir, targetBugDir);
                                logWithTestCase("已将确认的bug文件夹移动到: " + targetBugDir);
                            } catch (IOException e) {
                                logWithTestCase(Level.SEVERE, "移动bug文件夹失败: " + e.getMessage());
                            }
                        }
                        reportDir = targetBugDir;
                        break;
                    case "TESTCASE_ERROR":
                        fileName = "TestCaseErrorAnalysis.md";
                        reportDir = sourceTestCaseDir;
                        break;
                    default:
                        fileName = "WrongFormatReport.md";
                        reportDir = sourceTestCaseDir;
                        if (reportContent != null && !reportContent.isEmpty()) {
                            if (reportContent.contains("BugReport")) {
                                fileName = "BugReport.md";
                                if (Files.exists(sourceTestCaseDir)) {
                                    try {
                                        Files.move(sourceTestCaseDir, targetBugDir);
                                        logWithTestCase("已将确认的bug文件夹移动到 (WrongFormat): " + targetBugDir);
                                    } catch (IOException e) {
                                        logWithTestCase(Level.SEVERE, "移动bug文件夹失败 (WrongFormat): " + e.getMessage());
                                    }
                                }
                                reportDir = targetBugDir;
                            } else if (reportContent.contains("Test Case Issue Analysis") || reportContent.contains("Test Case Error Analysis")) {
                                fileName = "TestCaseErrorAnalysis.md";
                            }
                        }
                        LoggerUtil.logExec(Level.WARNING, "Unknown bug_type in report JSON: " + bugType);
                        break;
                }
                Files.createDirectories(reportDir);
                saveToFile(reportDir.resolve(fileName).toString(), reportContent);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "Failed to parse report JSON or create report directory. Saving raw output. Error: " + e.getMessage());
                fileName = "WrongFormatReport.md";
                reportContent = reportJson;
                try {
                    Path reportDir = Paths.get(bugReportPath, "testcase", testCaseName);
                    Files.createDirectories(reportDir);
                    saveToFile(reportDir.resolve(fileName).toString(), reportContent);
                } catch (IOException ex) {
                    LoggerUtil.logExec(Level.SEVERE, "Failed to save even raw report. Error: " + ex.getMessage());
                }
            }

            
            // 记录最终结果
            var result = fileName.replace(".md", "");
            LoggerUtil.logResult(Level.INFO, "BugVerifyAgent: " + testCase.getFile().getAbsolutePath() + " " + result);
            LoggerUtil.logExec(Level.INFO, "BugVerifyAgent: " + testCase.getFile().getAbsolutePath() + " " + result);
        }
        
        return reportJson;
    }
    
    /**
     * 增强验证流程 - 当初始验证返回bug时进行额外的验证步骤
     * 
     * 目标：通过多重验证和反方论证来确保bug判断的准确性
     * 
     * 流程：
     * 1. 第一步：进行两次额外验证，确保每次都是bug
     * 2. 第二步：生成测试用例问题解释，提供反方观点
     * 3. 第三步：使用裁决模型判断哪一方正确
     * 4. 如果裁决认为这是bug，继续进行分析；否则停止
     * 
     * @return 增强验证的结果：true表示确认是bug，false表示不是bug或验证失败
     */
    public boolean enhanceVerify() {
        String header = "Enhance Verification: " + getTestCaseIdentifier();
        LoggerUtil.logVerify(Level.INFO, header, "Verification process started.");
        
        // 前置条件检查：确保测试用例存在且被识别为bug
        if (testCase == null || testCase.getResult() == null) {
            logWithTestCase("测试用例未设置，跳过增强验证");
            this.enhanceVerifyFailureReason = "Test case not set.";
            LoggerUtil.logVerify(Level.WARNING, header, "Verification failed: Test case not set.");
            return false;
        }
        
        Path verifyContextPath = this.verifyContextPath;
        
        // ===== 第一步：多重验证确保一致性 =====
        // 目标：通过两次额外的验证来确保bug判断的稳定性
        // 如果任何一次验证不认为是bug，则停止增强验证流程
        List<String> verificationResults = new ArrayList<>();
        List<String> nonBugResults = new ArrayList<>();
        
        for (int i = 1; i <= 2; i++) {
            logWithTestCase("执行第 " + i + " 次额外验证");
            
            try {
                // 重新执行验证，确保结果的一致性
                testCase.verifyTestFail();

                // 保存每次验证的详细结果到文件
                String verificationResult = "{\"verification\": " + i + ", \"result\": \"" + (testCase.getResult().isBug() ? "BUG" : "NOT_BUG") +
                        "\", \"message\": \"" + testCase.verifyMessage + "\"}";
                saveToFile(verifyContextPath.resolve("enhance_verify_" + i + ".json").toString(), verificationResult);

                if (testCase.getResult().isBug()) {
                    verificationResults.add("验证 " + i + ": BUG - " + testCase.verifyMessage);
                    logWithTestCase("第 " + i + " 次验证确认是bug");
                } else {
                    nonBugResults.add("验证 " + i + ": NOT_BUG - " + testCase.verifyMessage);
                    logWithTestCase("第 " + i + " 次验证认为不是bug");
                    break;
                }

            } catch (Exception e) {
                logWithTestCase(Level.WARNING, "第 " + i + " 次验证失败: " + e.getMessage());
                nonBugResults.add("验证 " + i + ": ERROR - " + e.getMessage());
                
                // 保存错误信息到文件
                String errorResult = "{\"verification\": " + i + ", \"result\": \"ERROR\", \"message\": \"" + e.getMessage() + "\"}";
                saveToFile(verifyContextPath.resolve("enhance_verify_" + i + "_error.json").toString(), errorResult);
            }
        }
        
        // 检查验证一致性：所有验证都必须认为是bug才能继续
        boolean allBugResults = verificationResults.size() == 2;
        
        if (!allBugResults) {
            logWithTestCase("增强验证失败：不是所有验证都认为是bug");
            this.enhanceVerifyFailureReason = "Inconsistent verification results. Not all validations identified a bug.";
            
            // 保存验证失败结果
            StringBuilder failureSummary = new StringBuilder();
            failureSummary.append("# 增强验证失败\n\n");
            failureSummary.append("## 验证结果\n");
            for (String result : verificationResults) {
                failureSummary.append("- ").append(result).append("\n");
            }
            failureSummary.append("\n## 非Bug结果\n");
            for (String result : nonBugResults) {
                failureSummary.append("- ").append(result).append("\n");
            }
            saveToFile(verifyContextPath.resolve("enhance_verify_failed.md").toString(), failureSummary.toString());
            
            LoggerUtil.logVerify(Level.INFO, header, "Final Verdict: TESTCASE_ISSUE (Inconsistent results)");
            return false;
        }
        
        logWithTestCase("所有验证都确认是bug，进入第二步：生成测试用例问题解释");
        
        // ===== 第二步：生成反方论证 =====
        // 目标：专门寻找测试用例中的问题，提供反方观点
        // 这有助于避免误判，确保bug判断的准确性
        String testCaseIssueExplanation = generateTestCaseIssueExplanation();
        
        // 保存测试用例问题解释结果
        if (testCaseIssueExplanation != null && !testCaseIssueExplanation.isEmpty()) {
            saveToFile(verifyContextPath.resolve("testcase_issue_explanation.txt").toString(), testCaseIssueExplanation);
        } else {
            logWithTestCase(Level.WARNING, "测试用例问题解释生成失败");
            saveToFile(verifyContextPath.resolve("testcase_issue_explanation_failed.txt").toString(), "生成失败");
        }
        
        // ===== 第三步：裁决分析 =====
        // 目标：使用K2模型作为公正的裁决者，比较双方论证
        // 决定哪一方更有说服力：bug论证 vs 测试用例问题论证
        String verdict = performVerdictAnalysis(testCaseIssueExplanation);
        
        // ===== 第四步：基于裁决结果决定后续流程 =====
        // 如果裁决确认是bug，继续完整的分析流程
        // 如果裁决认为是测试用例问题，停止并返回增强验证结果
        if (verdict != null && !verdict.isEmpty()) {
            LoggerUtil.logVerify(Level.INFO, header, "Final Verdict: " + verdict);
            if ("BUG".equals(verdict)) {
                logWithTestCase("裁决确认是bug，继续进行分析");
                return true;
            } else if("UNCLEAR".equals(verdict)) {
                logWithTestCase("裁决结果不明确，无法判断");
                return true; // UNCLEAR需要继续判断。
            }   else {
                logWithTestCase("裁决认为是测试用例问题");
                this.enhanceVerifyFailureReason = "Verdict: " + verdict + ". The adjudicator determined the issue does not qualify as a bug.";
                return false;
            }
        } else {
            logWithTestCase(Level.WARNING, "裁决结果为空，无法判断");
            this.enhanceVerifyFailureReason = "Verdict is null or empty. Adjudication process failed.";
            LoggerUtil.logVerify(Level.WARNING, header, "Final Verdict: UNKNOWN (Adjudication failed)");
            return false;
        }
    }
    
    /**
     * 生成测试用例问题解释
     * 
     * 目标：专门分析测试用例中可能存在的问题，提供反方论证
     * 
     * 分析内容：
     * - API使用是否正确
     * - 测试逻辑是否有误
     * - 测试设置是否合理
     * - 假设是否成立
     * - 期望是否合理
     * 
     * 作用：避免误判，确保bug判断的准确性
     * 
     * @return 测试用例问题解释的JSON字符串，失败时返回null
     */
    private String generateTestCaseIssueExplanation() {
        LoggerUtil.logExec(Level.INFO, "生成测试用例问题解释");
        
        try {
            // 准备测试用例代码，添加行号以便LLM分析
            String source = testCase.getSourceWithoutComment();
            StringBuilder sb = new StringBuilder();
            var lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) {
                sb.append(i).append("\t:").append(lines[i]).append("\n");
            }
            var testcase = sb.toString();
            
            // 生成专门的测试用例问题分析prompt
            String prompt = PromptGen.generateTestCaseIssueExplanationPrompt(testcase, testOutput, testCase.getApiDoc());
            ArrayList<Tool<?>> tools = new ArrayList<>();
            tools.add(new TestCaseIssueExplanationTool());
            
            // 调用LLM进行分析，确保至少返回一个工具调用
            var result = llm.toolCallWithContent(prompt, tools);
            var callList = result.toolCalls();
            var content = result.content();
            if (callList.isEmpty()) {
                LoggerUtil.logExec(Level.WARNING, "No function call found in test case issue explanation");
                return null;
            }
            
            // 提取分析结果
            var explanationCall = callList.get(0);
            return content + "\n" + explanationCall.arguments.toString();
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "生成测试用例问题解释失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 执行裁决分析
     * 
     * 目标：使用K2模型作为公正的裁决者，比较双方论证并做出最终判断
     * 
     * 裁决过程：
     * 1. 收集bug论证（基于初始分析）
     * 2. 收集测试用例问题论证（来自专门的分析）
     * 3. 使用K2模型评估双方论证的强度
     * 4. 基于证据质量和逻辑性做出公正判断
     * 
     * 裁决结果：
     * - BUG：确认是JDK bug
     * - TESTCASE_ISSUE：确认是测试用例问题
     * - UNCLEAR：证据不足，无法确定
     * 
     * @param testCaseIssueExplanation 测试用例问题解释
     * @return 裁决结果字符串（"BUG", "TESTCASE_ISSUE", "UNCLEAR"），失败时返回null
     */
    private String performVerdictAnalysis(String testCaseIssueExplanation) {
        LoggerUtil.logExec(Level.INFO, "执行裁决分析");
        
        try {
            // 准备测试用例代码，添加行号以便LLM分析
            String source = testCase.getSourceWithoutComment();
            StringBuilder sb = new StringBuilder();
            var lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) {
                sb.append(i).append("\t:").append(lines[i]).append("\n");
            }
            var testcase = sb.toString();
            
            // 构建bug论证：基于初始的bug分析结果
            String bugArgument = initialAnalysis != null ? initialAnalysis : "{\"root_cause\": \"Initial analysis not available\"}";
            
            // 生成裁决分析prompt，包含双方论证
            String prompt = PromptGen.generateVerdictAnalysisPrompt(testcase, testOutput, testCase.getApiDoc(), bugArgument, testCaseIssueExplanation);
            ArrayList<Tool<?>> tools = new ArrayList<>();
            tools.add(new VerdictTool());
            
            // 调用K2模型进行裁决分析，确保至少返回一个工具调用
            var result = llm.toolCallWithContent(prompt, tools);
            var callList = result.toolCalls();
            var content = result.content();
            if (callList.isEmpty()) {
                LoggerUtil.logExec(Level.WARNING, "No function call found in verdict analysis");
                return "UNCLEAR";
            }
            
            // 提取裁决结果 - 直接从tool call arguments中获取verdict
            var verdictCall = callList.get(0);
            String verdict = (String) verdictCall.arguments.get("verdict");
            
            // 保存完整的裁决分析内容到文件（包含推理过程）
            String fullAnalysis = content + "\n" + verdictCall.arguments.toString();
            if (verifyContextPath != null) {
                saveToFile(verifyContextPath.resolve("verdict_full_analysis.txt").toString(), fullAnalysis);
            }
            
            LoggerUtil.logExec(Level.INFO, "裁决分析完成，结果: " + verdict);
            return verdict;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "执行裁决分析失败: " + e.getMessage());
            return "UNCLEAR";
        }
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
        
        int iterations = infoCollectionAgent.getNumIterations();

        String header = "Information Collection: " + getTestCaseIdentifier();
        String message = String.format(
            "  Items Collected: %d\n" +
            "  Total Size: %d chars\n" +
            "  Iterations: %d",
            collectedInfo.size(),
            collectedInfo.values().stream().mapToInt(v -> v.toString().length()).sum(),
            iterations
        );
        LoggerUtil.logVerify(Level.INFO, header, message);
    }
    
    /**
     * 保存完整的信息收集详细报告
     */
    private void saveDetailedInfoReport(String detailedReport) {
        if (verifyContextPath == null) return;
        
        try {
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
        if (verifyContextPath == null) return;
        
        try {
            // 创建info子目录
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
     * 支持消融实验：生成多个配置组合的prompt并获取结果
     */
    private String generateReport(List<String> hypotheses, Map<String, TestResult> verificationResults) {
        boolean enableAblationTest = edu.tju.ista.llm4test.config.GlobalConfig.isEnableAblationTest();
        
        if (enableAblationTest) {
            LoggerUtil.logExec(Level.INFO, "开始消融实验：生成3个配置组合");
            
            // 定义3个消融实验配置
            List<AblationConfig> configs = Arrays.asList(
                new AblationConfig(true, true, true, true, 1),   // 完整配置
                new AblationConfig(true, false, true, true, 2), // 无信息源
                new AblationConfig(false, true, true, true, 3)  // 无假设
            );
            
            // 并行执行3个配置
            var manager = ConcurrentExecutionManager.getInstance();
            List<CompletableFuture<AblationResult>> futures = new ArrayList<>();
            
            for (AblationConfig config : configs) {
                CompletableFuture<AblationResult> future = manager.submitLLMTask(() -> {
                    try {
                        String result = generateReportWithConfig(hypotheses, verificationResults, config);
                        LoggerUtil.logExec(Level.INFO, "消融实验配置 " + config.id + " 完成");
                        return new AblationResult(config, result);
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "消融实验配置 " + config.id + " 失败: " + e.getMessage());
                        return new AblationResult(config, 
                            "{\"bug_type\": \"ABLATION_ERROR\", \"report\": \"配置 " + config.id + " 执行失败\"}");
                    }
                });
                futures.add(future);
            }
            
            // 等待所有实验完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 保存所有结果
            List<AblationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            saveAblationResults(results);
            
            // 返回第一个配置的结果
            return results.get(0).result;
        } else {
            // 非消融实验，使用原有配置
            boolean includeHypothesis = edu.tju.ista.llm4test.config.GlobalConfig.isIncludeHypothesis();
            boolean includeInfoSource = edu.tju.ista.llm4test.config.GlobalConfig.isIncludeInfoSource();
            boolean useMinimizedTestcase = edu.tju.ista.llm4test.config.GlobalConfig.isUseMinimizedTestcase();
            boolean includeApiDocs = edu.tju.ista.llm4test.config.GlobalConfig.isIncludeApiDocs();
            
            AblationConfig config = new AblationConfig(includeHypothesis, includeInfoSource, 
                useMinimizedTestcase, includeApiDocs, 0);
            
            return generateReportWithConfig(hypotheses, verificationResults, config);
        }
    }
    
    /**
     * 使用指定配置生成报告
     */
    private String generateReportWithConfig(List<String> hypotheses, Map<String, TestResult> verificationResults, 
                                          AblationConfig config) {
        
        // 根据配置选择测试用例
        String actualTestCode = testCode;
        if (!config.useMinimizedTestcase && testCase.getOriginFile() != null) {
            try {
                actualTestCode = Files.readString(testCase.getOriginFile().toPath());
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "读取原始测试用例失败: " + e.getMessage());
            }
        }
        
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
        if (config.includeHypothesis) {
            for (String hypothesis : hypotheses) {
                hypothesesBuilder.append(hypothesis).append("\n\n");
            }
        }
        
        // 构建信息源内容
        StringBuilder infoSourceBuilder = new StringBuilder();
        if (config.includeInfoSource || config.includeApiDocs) {
            infoSourceBuilder.append("# 信息收集和分析过程\n\n");
            
            if (config.includeInfoSource) {
                buildInfoSourceContent(infoSourceBuilder);
            }
            
            if (testCase != null && config.includeApiDocs) {
                String apiInfoWithSource = testCase.getApiInfoWithSource();
                if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
                    infoSourceBuilder.append("\n# 测试用例API信息和源码\n\n").append(apiInfoWithSource).append("\n");
                }
            }
        }
        
        // 生成prompt并获取结果
        try {
            String prompt = PromptGen.generateBugVerifyBugReportPrompt(
                actualTestCode, testOutput, hypothesesBuilder.toString(),
                resultsBuilder.toString(), infoSourceBuilder.toString());
            
            // 保存prompt到文件
            savePromptToFile(prompt, config);
            
            String reportJson = llm.messageCompletion(prompt, 0.7, true);

            // 检查并修复JSON
            int maxRetries = 3;
            for (int i = 0; i < maxRetries; i++) {
                if (isValidJson(reportJson)) {
                    return reportJson;
                }
                logWithTestCase(Level.WARNING, "报告JSON格式不合法，尝试修复 (第 " + (i + 1) + " 次)");
                reportJson = fixJsonWithLLM(reportJson);
                // 移除 ```json 标记 增强鲁棒性
                reportJson = reportJson.trim().replaceAll("^```(json)?\\s*|\\s*```$", "");
             }
 
             logWithTestCase(Level.SEVERE, "报告JSON修复失败 " + maxRetries + " 次，返回错误报告");
             return "{\"bug_type\": \"JSON_FIX_FAILED\", \"report\": \"报告生成和修复失败\"}";

        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "生成报告失败: " + e.getMessage());
            return "{\"bug_type\": \"GENERATION_ERROR\", \"report\": \"生成报告失败\"}";
        }
    }

    private boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            new ObjectMapper().readTree(json);
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private String fixJsonWithLLM(String brokenJson) {
        try {
            String jsonFormat = "{\"bug_type\": \"<bug_type>\", \"report\": \"<markdown_report>\"}";
            String prompt = PromptGen.generateExtractJsonPrompt(brokenJson, jsonFormat);
            return OpenAI.DoubaoFlash.messageCompletion(prompt, 0.3, true);
        } catch (Exception e) {
            logWithTestCase(Level.SEVERE, "使用LLM修复JSON失败: " + e.getMessage());
            return brokenJson; // 返回原始的错误JSON
        }
    }
    
    /**
     * 消融实验配置类
     */
    private static class AblationConfig {
        final boolean includeHypothesis;
        final boolean includeInfoSource;
        final boolean useMinimizedTestcase;
        final boolean includeApiDocs;

        final int id;
        
        AblationConfig(boolean includeHypothesis, boolean includeInfoSource, 
                      boolean useMinimizedTestcase, boolean includeApiDocs, int id) {
            this.includeHypothesis = includeHypothesis;
            this.includeInfoSource = includeInfoSource;
            this.useMinimizedTestcase = useMinimizedTestcase;
            this.includeApiDocs = includeApiDocs;
            this.id = id;
        }
    }
    
    /**
     * 消融实验结果类
     */
    private static class AblationResult {
        final AblationConfig config;
        final String result;
        
        AblationResult(AblationConfig config, String result) {
            this.config = config;
            this.result = result;
        }
    }
    
    /**
     * 保存消融实验结果
     */
    private void saveAblationResults(List<AblationResult> results) {
        if (verifyContextPath == null) return;
        
        try {
            Path ablationDir = verifyContextPath.getParent().resolve("ablation_results");
            Files.createDirectories(ablationDir);
            
            // 保存每个配置的结果
            for (AblationResult result : results) {
                String fileName = "ablation_config_" + result.config.id + ".json";
                saveToFile(ablationDir.resolve(fileName).toString(), result.result);
            }
            
            // 保存对比摘要
            StringBuilder summary = new StringBuilder();
            summary.append("# 消融实验结果摘要\n\n");
            summary.append("共执行了 ").append(results.size()).append(" 个配置组合\n\n");
            
            for (AblationResult result : results) {
                summary.append("## 配置 ").append(result.config.id).append("\n");
                summary.append("- 包含假设: ").append(result.config.includeHypothesis).append("\n");
                summary.append("- 包含信息源: ").append(result.config.includeInfoSource).append("\n");
                summary.append("- 使用最小化测试用例: ").append(result.config.useMinimizedTestcase).append("\n");
                summary.append("- 包含API文档: ").append(result.config.includeApiDocs).append("\n\n");
            }
            
            saveToFile(ablationDir.resolve("ablation_summary.md").toString(), summary.toString());
            
            LoggerUtil.logExec(Level.INFO, "已保存消融实验结果到: " + ablationDir);
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存消融实验结果失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建信息源内容
     */
    private void buildInfoSourceContent(StringBuilder infoSourceBuilder) {
        // 添加信息收集统计
        Map<InformationCollectionAgent.InfoType, Integer> typeStats = new HashMap<>();
        Map<InformationCollectionAgent.InfoType, Integer> typeSizes = new HashMap<>();
        
        // 统计各类型信息
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                String content = (String) entry.getValue();
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
        
        // 添加完整信息
        infoSourceBuilder.append("## 完整信息源内容\n\n");
        int sourceCount = 0;
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                sourceCount++;
                infoSourceBuilder.append("### 信息源 ").append(sourceCount).append(": ").append(entry.getKey()).append("\n\n");
                infoSourceBuilder.append((String) entry.getValue()).append("\n\n");
            }
        }
    }
    
    /**
     * 保存生成报告的完整prompt到文件
     */
    private void savePromptToFile(String prompt, AblationConfig config) {
        if (verifyContextPath == null) return;
        
        try {
            Path promptsDir = verifyContextPath.resolve("prompts");
            Files.createDirectories(promptsDir);
            
            // 根据配置生成文件名
            String promptFileName;
            if (config.id == 0) {
                // 非消融实验
                promptFileName = "generate_report_prompt.txt";
            } else {
                // 消融实验配置
                promptFileName = String.format("generate_report_prompt_config_%d.txt", config.id);
            }
            
            saveToFile(promptsDir.resolve(promptFileName).toString(), prompt);
            
            LoggerUtil.logExec(Level.INFO, "已保存生成报告的prompt: " + promptFileName);
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存prompt失败: " + e.getMessage());
        }
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

                        // 运行测试获取输出
                        JtregExecuteTool jtregTool = new JtregExecuteTool();
                        ToolResponse<TestResult> response = jtregTool.execute(testcase.getFile().toPath(), testCaseName);

                        if (response.isSuccess()) {
                            testcase.setResult(response.getResult());
                        } else {
                            LoggerUtil.logVerify(Level.SEVERE, testcase.getFile().getAbsolutePath() + ": 无法获取测试输出");
                        }

                        agent.setTestCase(testcase);


                        boolean isBug = agent.enhanceVerify();

                        if (isBug) {
                            LoggerUtil.logVerify(Level.INFO, "Test case is a confirmed bug, proceeding to analysis: " + testCaseName);
                            agent.analyze();
                        } else {
                            LoggerUtil.logVerify(Level.INFO, "Test case is not a bug: " + testCaseName);
                            agent.generateNonBugReport(); // Generate report for non-bugs
                        }

                        LoggerUtil.logExec(Level.INFO, "Bug verification process finished for: " + testCaseName);
                        
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
            if (response.isSuccess()) {
                testcase.setResult(response.getResult());
            } else {
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
     * 设置验证环境：拷贝整个test目录到verify目录
     * @return verify目录路径，失败时返回null
     */
    private Path setupVerifyEnvironment() {
        try {
            // 创建verify根目录
            Path verifyRootDir = Paths.get("verify");
            Files.createDirectories(verifyRootDir);
            
            // 源test目录
            Path sourceTestDir = Paths.get(GlobalConfig.getTestDir());
            if (!Files.exists(sourceTestDir)) {
                LoggerUtil.logExec(Level.WARNING, "Source test directory not found: " + sourceTestDir);
                return null;
            }
            
            // 目标verify目录（包含时间戳以避免冲突）
            String timestamp = String.valueOf(System.currentTimeMillis());
            Path targetVerifyDir = verifyRootDir.resolve("test_" + timestamp);
            
            // 复制整个test目录
            LoggerUtil.logExec(Level.INFO, "Copying test environment: " + sourceTestDir + " -> " + targetVerifyDir);
            FileUtils.copyDirectory(sourceTestDir.toFile(), targetVerifyDir.toFile());
            
            LoggerUtil.logExec(Level.INFO, "Verify environment setup complete: " + targetVerifyDir);
            return targetVerifyDir;
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to setup verify environment: " + e.getMessage());
            return null;
        }
    }

    /**
     * 更新测试用例路径到verify目录
     * @param originalTestCase 原始测试用例
     * @param verifyDir verify目录路径
     * @return 更新后的测试用例，失败时返回null
     */
    private TestCase updateTestCaseToVerifyDir(TestCase originalTestCase, Path verifyDir) {
        try {
            // 计算相对路径：从test目录到具体测试文件
            Path testDir = Paths.get(GlobalConfig.getTestDir()).toAbsolutePath();
            Path originalPath = originalTestCase.getFile().toPath().toAbsolutePath();
            logWithTestCase(Level.INFO, "Updating test case paths: testDir=" + testDir + ", originalPath=" + originalPath);
            Path relativePath = testDir.relativize(originalPath);
            logWithTestCase(Level.INFO, "Calculated relativePath: " + relativePath);

            // 在verify目录中的新路径
            Path newTestPath = verifyDir.resolve(relativePath);
            
            if (!Files.exists(newTestPath)) {
                logWithTestCase(Level.WARNING, "Test file not found in verify dir: " + newTestPath);
                return null;
            }
            
            // 创建新的TestCase对象
            TestCase verifyTestCase = new TestCase(newTestPath.toFile());
            verifyTestCase.setOriginFile(originalTestCase.getFile()); // 保持原始文件引用
            verifyTestCase.setResult(originalTestCase.getResult());    // 保持测试结果
            verifyTestCase.verifyMessage = originalTestCase.verifyMessage; // 保持验证信息
            
            // 设置API文档处理器（使用配置创建新的处理器以保证一致性）
            verifyTestCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());
            
            logWithTestCase(Level.INFO, "Test case updated to verify environment: " + newTestPath);
            return verifyTestCase;
            
        } catch (Exception e) {
            logWithTestCase(Level.SEVERE, "Failed to update test case to verify dir: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成非Bug情况的报告
     * 当 enhanceVerify 确定不是一个bug时调用此方法
     */
    private void generateNonBugReport() {
        if (testCaseName == null) {
            LoggerUtil.logExec(Level.WARNING, "Cannot generate non-bug report: testCaseName is null.");
            return;
        }

        try {
            // 报告应保存在 testcase 目录下的特定测试文件夹中
            Path testcaseReportDir = Paths.get(bugReportPath, "testcase", testCaseName);
            Files.createDirectories(testcaseReportDir);

            // 创建报告内容
            String reportContent = String.format(
                "# Test Case Analysis Report\n\n" +
                "**Test Case**: `%s`\n\n" +
                "This test case was determined **not to be a bug** during the enhanced verification process.\n\n" +
                "## Reason for Failure\n\n" +
                "**Step Failed**: Enhanced Verification\n" +
                "**Reason**: %s\n",
                testCaseName,
                enhanceVerifyFailureReason
            );

            // 保存报告文件
            String reportFileName = "NotABugAnalysis.md";
            saveToFile(testcaseReportDir.resolve(reportFileName).toString(), reportContent);
            
            LoggerUtil.logExec(Level.INFO, "Non-bug report generated for " + testCaseName + " at " + testcaseReportDir.resolve(reportFileName));

        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to generate non-bug report for " + testCaseName + ": " + e.getMessage());
        }
    }
}
