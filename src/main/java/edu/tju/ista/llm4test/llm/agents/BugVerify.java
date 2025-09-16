package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.prompt.PromptGen;
import freemarker.template.TemplateException;
import org.apache.commons.io.FileUtils;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static edu.tju.ista.llm4test.utils.FileUtils.saveToFile;
import static edu.tju.ista.llm4test.utils.FileUtils.appendToFile;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import java.util.Date;

public class BugVerify extends Agent {

    // 可用的工具
    private final JtregExecuteTool jtregTool;
    
    // 新增的信息收集Agent
    private final InformationCollectionAgent infoCollectionAgent;
    // Agent for test case minimization
    private final TestCaseAgent minimizationAgent;
    
    // Agent for hypothesis formation and verification (暂时未使用)
    @SuppressWarnings("unused")
    private final HypothesisAgent hypothesisAgent;
    
    // LLM实例
    private final OpenAI llm = OpenAI.AgentModel;

    // 全局结果目录，仅生成一次
    private static final String GLOBAL_RESULT_TIMESTAMP = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    private static final Path GLOBAL_RESULT_DIR = Paths.get("result", GLOBAL_RESULT_TIMESTAMP);
    static {
        try {
            Files.createDirectories(GLOBAL_RESULT_DIR);
        } catch (IOException e) {
            // 静态初始化失败记录日志
            LoggerUtil.logExec(Level.WARNING, "创建全局结果目录失败: " + e.getMessage());
        }
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
        this.testCode = testCase.getSourceCode();
        this.testOutput = testCase.getResult().getOutput();

        // 保存原始（未约简）测试用例与原始执行输出
        try {
            if (testCase.getOriginFile() != null) {
                this.originalTestCode = Files.readString(testCase.getOriginFile().toPath());
            } else {
                this.originalTestCode = this.testCode;
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "读取原始测试用例失败: " + e.getMessage());
            this.originalTestCode = this.testCode;
        }
        if (testCase.getResult() != null && testCase.getResult().getOutput() != null) {
            this.originalTestOutput = testCase.getResult().getOutput();
        } else {
            this.originalTestOutput = this.testOutput;
        }

        // 创建验证上下文文件夹
        createVerifyContextFolder();
    }

    // 分析状态
    private TestCase testCase;
    @SuppressWarnings("unused")
    private String originalTestCode;
    private String originalTestOutput;
    private String testCode;
    private String testOutput;
    private String initialAnalysis;
    private Map<String, Object> collectedInfo = new HashMap<>();
    private Boolean enhancePassed = null;

    private String enhanceVerifyFailureReason = "Unknown";
    
    
    // 报告输出路径
    private String bugReportPath = "BugReport";
    private String verifyContextFolder = null;
    private String testCaseName = null;
    private Path verifyContextPath = null;
    private Path sharedVerifyDir = null;
    
    // ReduceResult 缓存目录与名单
    private static final Path REDUCE_CACHE_DIR = Paths.get("ReduceResult");
    private static final Path REDUCE_MANIFEST = REDUCE_CACHE_DIR.resolve("reduced_list.txt");
    
    // 缓存与名单的线程安全锁与内存缓存
    private static final Object REDUCE_CACHE_FS_LOCK = new Object();
    private static final java.util.Set<String> reducedTestManifest = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean manifestLoaded = false;

    
    // 信息源标记
    private int infoCounter = 0;
    private Map<String, String> infoSourceMap = new HashMap<>();

    // 使用全局结果目录，无需实例字段
    
    public void setSharedVerifyDir(Path sharedVerifyDir) {
        this.sharedVerifyDir = sharedVerifyDir;
    }

    /**
     * 加载约简名单。此方法不是线程安全的，应在任何并发操作开始前，
     * 在一个单线程上下文中调用（例如，在 verifyBugsFromLog 的开头）。
     */
    private static void loadManifestIfNeeded() {
        if (manifestLoaded) {
            return;
        }
        if (Files.exists(REDUCE_MANIFEST)) {
            try (java.util.stream.Stream<String> lines = Files.lines(REDUCE_MANIFEST)) {
                lines.filter(line -> !line.isBlank())
                     .map(line -> line.trim().replace('\\', '/'))
                     .forEach(reducedTestManifest::add);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "[BugVerify] 读取 ReduceResult 名单失败: " + e.getMessage());
            }
        }
        manifestLoaded = true;
    }

    // 简化：将约简准备与缓存复用抽成一个小方法，减少 analyze 体积
    private static class MinimizationPrepResult {
        final TestCase verifyTestCase;
        final boolean skipMinimization;
        MinimizationPrepResult(TestCase tc, boolean skip) { this.verifyTestCase = tc; this.skipMinimization = skip; }
    }

    private MinimizationPrepResult prepareMinimizationIfNeeded(Path verifyContextPath) {
        try {
            Path testDir = Paths.get(GlobalConfig.getTestDir()).toAbsolutePath();
            
            // 修复：优先使用原始文件路径，避免验证目录路径计算问题
            Path originalPath;
            if (this.testCase.getOriginFile() != null) {
                originalPath = this.testCase.getOriginFile().toPath().toAbsolutePath();
            } else {
                Path currentPath = this.testCase.getFile().toPath().toAbsolutePath();
                // 如果当前文件在验证目录中，直接回退到共享目录准备
                if (currentPath.toString().contains("/verify/")) {
                    logWithTestCase(Level.INFO, "文件在验证目录中，直接使用共享目录准备");
                    TestCase verifyTestCase = prepareTestCaseInSharedDir(this.testCase, this.sharedVerifyDir);
                    return new MinimizationPrepResult(verifyTestCase, false);
                }
                originalPath = currentPath;
            }
            
            Path relativePath;
            try {
                relativePath = testDir.relativize(originalPath).normalize();
            } catch (IllegalArgumentException iae) {
                logWithTestCase(Level.SEVERE, "计算缓存相对路径失败: testDir=" + testDir + " original=" + originalPath + " — " + iae.getMessage());
                // 回退：在共享目录中准备用例并继续最小化流程
                TestCase verifyTestCase = prepareTestCaseInSharedDir(this.testCase, this.sharedVerifyDir);
                return new MinimizationPrepResult(verifyTestCase, false);
            }

            String normalizedPath = relativePath.toString().replace(java.io.File.separator, "/");
            Path cachedFile = REDUCE_CACHE_DIR.resolve(relativePath);
            

            // 命中缓存：检查内存Set并且物理文件存在
            if (reducedTestManifest.contains(normalizedPath) && Files.exists(cachedFile)) {
                logWithTestCase(Level.INFO, "约简缓存命中，复用结果: " + normalizedPath);

                // 仅拷贝约简后的文件到共享目录
                Path newTestPath = this.sharedVerifyDir.resolve(relativePath).normalize();
                
                // 安全检查：确保新路径在共享验证目录内
                if (!newTestPath.startsWith(this.sharedVerifyDir)) {
                    logWithTestCase(Level.SEVERE, "计算的路径不在共享验证目录内，跳过缓存: " + newTestPath);
                    TestCase verifyTestCase = prepareTestCaseInSharedDir(this.testCase, this.sharedVerifyDir);
                    return new MinimizationPrepResult(verifyTestCase, false);
                }
                Files.createDirectories(newTestPath.getParent());
                Files.copy(cachedFile, newTestPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                TestCase verifyTestCase = new TestCase(newTestPath.toFile());
                verifyTestCase.setOriginFile(this.testCase.getFile());
                verifyTestCase.setResult(this.testCase.getResult());
                verifyTestCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());

                try {
                    ToolResponse<TestResult> response = jtregTool.execute(verifyTestCase.getFile().toPath(), verifyTestCase.name);
                    if (response.isSuccess()) {
                        verifyTestCase.setResult(response.getResult());
                    }
                } catch (Exception ignored) {
                    logWithTestCase(Level.WARNING, "约简后测试用例执行失败: " + ignored.getMessage());
                }

                String minimizedCode = verifyTestCase.getSourceCode();
                try {
                    Path minimizedFilePath = verifyContextPath.resolve(this.testCase.getFile().getName().replace(".java", "_minimized.java"));
                    Files.writeString(minimizedFilePath, minimizedCode);
                    saveToFile(verifyContextPath.resolve("minimized_output.txt").toString(), verifyTestCase.getResult() != null ? verifyTestCase.getResult().getOutput() : "");
                } catch (Exception ignored) {}

                this.testCode = TestCaseAgent.codeWithLineNumber(minimizedCode);
                this.testOutput = verifyTestCase.getResult() != null ? verifyTestCase.getResult().getOutput() : this.testOutput;
                this.testCase = verifyTestCase;
                this.testCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());
                this.testCase.recalculateApiDocs();

                return new MinimizationPrepResult(verifyTestCase, true);
            }

            // 未命中缓存：只做共享目录内用例恢复
            TestCase verifyTestCase = prepareTestCaseInSharedDir(this.testCase, this.sharedVerifyDir);
            return new MinimizationPrepResult(verifyTestCase, false);
        } catch (Exception e) {
            logWithTestCase(Level.WARNING, "prepareMinimizationIfNeeded failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取测试用例标识符，用于日志记录
     */
    private String getTestCaseIdentifier() {
        if (testCase != null && testCase.getFile() != null) {
            // 使用绝对路径以避免不同目录下的同名文件冲突
            return testCase.getFile().getAbsolutePath();
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
            saveToFile(verifyContextPath.resolve("original_output.txt").toString(), originalTestOutput);
           
            
            logWithTestCase("创建验证上下文文件夹: " + verifyContextPath);
        } catch (IOException e) {
            logWithTestCase(Level.WARNING, "创建验证上下文文件夹失败: " + e.getMessage());
        }
    }
    

    /**
     * 执行完整的Bug验证流程
     */
    public boolean analyze() {

        // 使用全局结果目录
        logWithTestCase("结果目录: " + GLOBAL_RESULT_DIR);

        Path sourceTestCaseDir = Paths.get(bugReportPath, "testcase", testCaseName);
        Path verifyContextPath = sourceTestCaseDir.resolve(verifyContextFolder);

        // 先进行增强验证（在消融模式下也执行，但不拦截后续步骤）
        boolean ablationMode = edu.tju.ista.llm4test.config.GlobalConfig.isEnableAblationTest();
        boolean isBug;
        boolean enhanced = this.enhanceVerify();
        this.enhancePassed = enhanced;
        if (!ablationMode && !enhanced) {
            return false;
        }
        isBug = true;

        // 1. TestCase Reproduce & reduce - 在完整环境中进行约简（必要时执行一次，供需要使用约简结果的配置复用）
        if (this.testCase != null && this.testCase.getResult() != null && this.testCase.getResult().isFail() && this.sharedVerifyDir != null) {
            logWithTestCase("Verified failure detected. Preparing shared verify environment for minimization: " + testCase.name);
            MinimizationPrepResult prep = prepareMinimizationIfNeeded(verifyContextPath);

            if (prep != null && !prep.skipMinimization && prep.verifyTestCase != null) {
                // 仅在未命中缓存时运行约简
                logWithTestCase("Starting test case minimization in shared verify environment for: " + prep.verifyTestCase.name);
                int originalLength = prep.verifyTestCase.getSourceCode().length();
                try {
                    TestCase minimizedCase = minimizationAgent.run(prep.verifyTestCase, this.sharedVerifyDir);

                    if (minimizedCase != null && minimizedCase.getSourceCode() != null && !minimizedCase.getSourceCode().equals(testCase.getSourceCode())) {
                        String minimizedCode = minimizedCase.getSourceCode();
                        Path minimizedFilePath = verifyContextPath.resolve(this.testCase.getFile().getName());
                        Files.writeString(minimizedFilePath, minimizedCode);

                        File minimizedFile = minimizedFilePath.toFile();
                        logWithTestCase("Minimization successful in verify environment. Minimized code saved at: " + minimizedFile.getAbsolutePath());

                        this.testCode = TestCaseAgent.codeWithLineNumber(minimizedCode);
                        this.testOutput = minimizedCase.getResult().getOutput();
                        this.testCase = minimizedCase;
                        this.testCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());
                        this.testCase.recalculateApiDocs();

                        saveToFile(verifyContextPath.resolve("minimized_output.txt").toString(), this.testOutput);
                        logWithTestCase("BugVerifyAgent will now proceed with the minimized test case from verify environment.");
                        // 写入缓存与名单
                        cacheMinimizedFileAndUpdateManifest();
                        // 约简统计
                        writeMinimizationStatus(true, originalLength, minimizedCode.length());
                    } else {
                        logWithTestCase(Level.WARNING, "Minimization process in verify environment did not reduce the test case. Continuing with the original test case.");
                        writeMinimizationStatus(false, originalLength, originalLength);
                    }
                } catch (Exception e) {
                    logWithTestCase(Level.SEVERE, "An exception occurred during test case minimization in verify environment. Continuing with the original test case. " + e);
                }
            }
        } else if (this.sharedVerifyDir == null) {
            logWithTestCase(Level.WARNING, "Shared verify directory not set, skipping minimization.");
        }

        LoggerUtil.logVerify(Level.INFO, "Test case is a confirmed bug, proceeding to analysis: " + testCaseName);
        logWithTestCase("开始Bug验证流程");

        // 1. 初始分析
        String initialInsight = performInitialAnalysis();
        saveToFile(verifyContextPath.resolve("initial_insight.json").toString(), initialInsight);

        logWithTestCase("初始分析完成：" + initialInsight);
        
        // 保存初始分析结果


        
        // 2. 收集信息（执行一次，供配置选择是否使用）
        collectRelevantInformation(initialInsight);
        logWithTestCase("信息收集完成，共 " + collectedInfo.size() + " 项");
        // 保存收集到的信息
        saveCollectedInfo();
        List<String> hypotheses = new ArrayList<>();
        Map<String, TestResult> verificationResults = new HashMap<>();

        // 4. 形成结论和报告
        String reportJson = generateReport(hypotheses, verificationResults);
        logWithTestCase("Bug验证报告已生成");


        isBug = false; //reset to false
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
                        isBug = true;
                        fileName = "BugReport.md";
                        reportDir = sourceTestCaseDir; // Default to source path in case of any failure

                        if (Files.exists(sourceTestCaseDir)) {
                            if (Files.exists(targetBugDir)) {
                                // Target exists, so we must merge.
                                logWithTestCase(Level.WARNING, "目标文件夹 " + targetBugDir + " 已存在，将合并内容。");
                                try {
                                    FileUtils.copyDirectory(sourceTestCaseDir.toFile(), targetBugDir.toFile());
                                    FileUtils.deleteDirectory(sourceTestCaseDir.toFile());
                                    logWithTestCase("文件夹内容合并完成。");
                                    reportDir = targetBugDir; // Success, update report dir
                                } catch (IOException mergeEx) {
                                    logWithTestCase(Level.SEVERE, "合并文件夹失败: " + mergeEx.getMessage() + "。报告将保存在原位置。");
                                    // On merge failure, reportDir remains sourceTestCaseDir (its default value)
                                }
                            } else {
                                // Target does not exist, so we can try a clean move.
                                try {
                                    // 修复：在移动之前，确保目标父目录存在
                                    Files.createDirectories(targetBugDir.getParent());
                                    Files.move(sourceTestCaseDir, targetBugDir);
                                    logWithTestCase("已将确认的bug文件夹移动到: " + targetBugDir);
                                    reportDir = targetBugDir; // Success, update report dir
                                } catch (IOException moveEx) {
                                    logWithTestCase(Level.SEVERE, "移动bug文件夹失败: " + moveEx.getMessage() + "。报告将保存在原位置。");
                                    // On move failure, reportDir remains sourceTestCaseDir (its default value)
                                }
                            }
                        } else {
                            // Source does not exist, which is strange, but the report should be in the target bug directory.
                            reportDir = targetBugDir;
                        }
                        break;
                    case "TESTCASE_ERROR":
                        fileName = "TestCaseErrorAnalysis.md";
                        reportDir = sourceTestCaseDir;
                        break;
                    case "UNCLEAR":
                        fileName = "UNCLEAR.md";
                        reportDir = sourceTestCaseDir;
                        break;
                    default:
                        fileName = "WrongFormatReport.md";
                        reportDir = sourceTestCaseDir;
                        if (reportContent != null && !reportContent.isEmpty()) {
                            if (reportContent.contains("BugReport") || reportContent.contains("BUG REPORT")) {
                                fileName = "BugReport.md";
                                if (Files.exists(sourceTestCaseDir)) {
                                    try {
                                        // 修复：在移动之前，确保目标父目录存在
                                        Files.createDirectories(targetBugDir.getParent());
                                        Files.move(sourceTestCaseDir, targetBugDir);
                                        logWithTestCase("已将确认的bug文件夹移动到 (WrongFormat): " + targetBugDir);
                                    } catch (IOException e) {
                                        logWithTestCase(Level.SEVERE, "移动bug文件夹失败 (WrongFormat): " + e.getMessage());
                                    }
                                }
                                reportDir = targetBugDir;
                            } else if (reportContent.contains("Test Case Issue Analysis") || reportContent.contains("Test Case Error Analysis")) {
                                fileName = "TestCaseErrorAnalysis.md";
                            } else {
                                reportContent += "Report Type: " + bugType + "\n\n";
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
        
        return isBug;
    }

    public boolean enhanceVerify() {
        return enhanceVerify(1);
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
    public boolean enhanceVerify(int retries) {
        String header = "Enhance Verification: " + getTestCaseIdentifier();
        LoggerUtil.logVerify(Level.INFO, header, "Verification process started.");
        
        // 前置条件检查：确保测试用例存在且被识别为bug
        if (testCase == null || testCase.getResult() == null) {
            logWithTestCase("测试用例未设置，跳过增强验证");
            this.enhanceVerifyFailureReason = "Test case not set.";
            LoggerUtil.logVerify(Level.WARNING, header, "Verification failed: Test case not set.");
            // 最终一次性写入结果
            writeVerifyStatus("Verdict", "TESTCASE_ISSUE", "Precondition failed: Test case not set.");
            return false;
        }
        
        Path verifyContextPath = this.verifyContextPath;
        
        // ===== 第一步：多重验证确保一致性 (投票机制) =====
        // 目标：通过三次验证并投票来确保bug判断的稳定性
        List<String> verificationLog = new ArrayList<>();
        List<String> bugArguments = new ArrayList<>();
        int bugVerificationCount = 0;
        final int TOTAL_VERIFICATIONS = 2;

        for (int i = 1; i <= TOTAL_VERIFICATIONS; i++) {
            logWithTestCase("执行第 " + i + " 次额外验证");
            
            try {
                // 重新执行验证，确保结果的一致性
                testCase.verifyTestFail();

                // 保存每次验证的详细结果到文件
                String verificationResult = "{\"verification\": " + i + ", \"result\": \"" + (testCase.getResult().isBug() ? "BUG" : "NOT_BUG") +
                        "\", \"message\": \"" + testCase.verifyMessage + "\"}";
                saveToFile(verifyContextPath.resolve("enhance_verify_" + i + ".json").toString(), verificationResult);

                if (testCase.getResult().isBug()) {
                    bugVerificationCount++;
                    verificationLog.add("验证 " + i + ": BUG - " + testCase.verifyMessage);
                    bugArguments.add(testCase.verifyMessage);
                    logWithTestCase("第 " + i + " 次验证确认是bug");
                } else {
                    verificationLog.add("验证 " + i + ": NOT_BUG - " + testCase.verifyMessage);
                    logWithTestCase("第 " + i + " 次验证认为不是bug");
                }

            } catch (Exception e) {
                logWithTestCase(Level.WARNING, "第 " + i + " 次验证失败: " + e.getMessage());
                verificationLog.add("验证 " + i + ": ERROR - " + e.getMessage());
                
                // 保存错误信息到文件
                String errorResult = "{\"verification\": " + i + ", \"result\": \"ERROR\", \"message\": \"" + e.getMessage() + "\"}";
                saveToFile(verifyContextPath.resolve("enhance_verify_" + i + "_error.json").toString(), errorResult);
            }
        }
        
        // 检查验证一致性：至少有3次验证认为是bug才能继续
        boolean isBugConfirmedByMajority = bugVerificationCount >= TOTAL_VERIFICATIONS;
        
        if (!isBugConfirmedByMajority) {
//            if (bugVerificationCount >= TOTAL_VERIFICATIONS - 1 && retries > 0) {
            if (retries > 0) {
                // 接近多数票，尝试重试以提升召回
                LoggerUtil.logVerify(Level.INFO, header,
                    "Majority not reached (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ") — retrying. Retries remaining: " + retries);
                return this.enhanceVerify(retries - 1);
            }

            // 不重试：要么票数差距较大，要么已无剩余重试次数
            if (retries <= 0) {
                LoggerUtil.logVerify(Level.INFO, header,
                    "Majority not reached (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ") — no retries left. Finalizing as TESTCASE_ISSUE.");
            } else {
                LoggerUtil.logVerify(Level.INFO, header,
                    "Majority not reached (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ") — not close enough to retry. Finalizing as TESTCASE_ISSUE.");
            }

            logWithTestCase("增强验证失败：多数验证 (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ") 未能确认是bug");
            this.enhanceVerifyFailureReason = "Inconsistent verification results. Only " + bugVerificationCount + " out of " + TOTAL_VERIFICATIONS + " validations identified a bug.";
            
            // 保存验证失败结果
            StringBuilder failureSummary = new StringBuilder();
            failureSummary.append("# 增强验证失败\n\n");
            failureSummary.append("## 验证结果 (").append(bugVerificationCount).append("/").append(TOTAL_VERIFICATIONS).append(" a BUG)\n");
            for (String result : verificationLog) {
                failureSummary.append("- ").append(result).append("\n");
            }
            saveToFile(verifyContextPath.resolve("enhance_verify_failed.md").toString(), failureSummary.toString());
            // 最终一次性写入结果
            writeVerifyStatus("Verdict", "TESTCASE_ISSUE", "Majority not reached" + " (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ")");
            LoggerUtil.logVerify(Level.INFO, header, "Final Verdict: TESTCASE_ISSUE (Inconsistent results)");
            return false;
        }
        
        logWithTestCase("多数验证 (" + bugVerificationCount + "/" + TOTAL_VERIFICATIONS + ") 确认是bug，进入第二步：生成测试用例问题解释");
        
        // ===== 第二步：生成反方论证 (3次) =====
        // 目标：专门寻找测试用例中的问题，提供反方观点
        List<String> testCaseIssueExplanations = new ArrayList<>();
        for (int i = 1; i <= TOTAL_VERIFICATIONS; i++) {
            logWithTestCase("生成第 " + i + " 次测试用例问题解释");
            String explanation = generateTestCaseIssueExplanation();
            if (explanation != null && !explanation.isEmpty()) {
                testCaseIssueExplanations.add(explanation);
                saveToFile(verifyContextPath.resolve("testcase_issue_explanation_" + i + ".txt").toString(), explanation);
            } else {
                logWithTestCase(Level.WARNING, "第 " + i + " 次测试用例问题解释生成失败");
                saveToFile(verifyContextPath.resolve("testcase_issue_explanation_" + i + "_failed.txt").toString(), "生成失败");
            }
        }

        if (testCaseIssueExplanations.isEmpty()) {
            logWithTestCase(Level.SEVERE, "所有测试用例问题解释都生成失败，中止验证");
            this.enhanceVerifyFailureReason = "All attempts to generate test case issue explanations failed.";
            LoggerUtil.logVerify(Level.SEVERE, header, "Final Verdict: UNKNOWN (Explanation generation failed)");
            // 最终一次性写入结果
            writeVerifyStatus("Verdict", "UNKNOWN", "Explanation generation failed");
            return false; // 中止流程
        }
        
        // ===== 第三步：裁决分析 (3次投票) =====
        String bugArgumentJson;
        String testCaseExplanationsJson;
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            bugArgumentJson = objectMapper.writeValueAsString(bugArguments);
            testCaseExplanationsJson = objectMapper.writeValueAsString(testCaseIssueExplanations);
        } catch (IOException e) {
            logWithTestCase(Level.SEVERE, "无法将论点序列化为JSON: " + e.getMessage());
            this.enhanceVerifyFailureReason = "Failed to serialize arguments to JSON.";
            LoggerUtil.logVerify(Level.SEVERE, header, "Final Verdict: UNKNOWN (JSON serialization failed)");
            // 最终一次性写入结果
            writeVerifyStatus("Verdict", "UNKNOWN", "JSON serialization failed");
            return false;
        }

        int totalScore = 0;
        List<String> verdictResultsForLog = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            logWithTestCase("执行第 " + i + " 次裁决分析");
            String verdict = performVerdictAnalysis(bugArgumentJson, testCaseExplanationsJson);
            verdictResultsForLog.add("裁决 " + i + ": " + verdict);
            if (verdict != null) {
                switch (verdict) {
                    case "BUG":
                        totalScore += 1;
                        break;
                    case "TESTCASE_ISSUE":
                        totalScore -= 2; // more weight to avoid false positive
                        break;
                    case "UNCLEAR":
                        // 0分，不操作
                        break;
                    default:
                        logWithTestCase(Level.WARNING, "未知的裁决结果: " + verdict);
                        break;
                }
            } else {
                logWithTestCase(Level.WARNING, "第 " + i + " 次裁决分析返回空结果");
            }
        }

        String finalVerdict;
        if (totalScore >= 2) {
            finalVerdict = "BUG";
        } else if (totalScore <= -2) { // lower threshold for TESTCASE_ISSUE
            finalVerdict = "TESTCASE_ISSUE";
        } else {
            finalVerdict = "UNCLEAR";
        }
        
        logWithTestCase("裁决投票完成. 总分: " + totalScore + ". 最终裁决: " + finalVerdict);
        // 新增：记录最终裁决并保存状态
        writeVerifyStatus("Verdict", finalVerdict, "Total score: " + totalScore);

        // 保存投票过程用于调试
        StringBuilder verdictSummary = new StringBuilder("# 裁决投票过程\n\n");
        verdictSummary.append("## Bug方论点 (").append(bugArguments.size()).append("条)\n\n```json\n").append(bugArgumentJson).append("\n```\n\n");
        verdictSummary.append("## TestCase方论点 (").append(testCaseIssueExplanations.size()).append("条)\n\n```json\n").append(testCaseExplanationsJson).append("\n```\n\n");
        verdictSummary.append("## 投票结果\n\n");
        verdictResultsForLog.forEach(res -> verdictSummary.append("- ").append(res).append("\n"));
        verdictSummary.append("\n**总分**: ").append(totalScore).append("\n");
        verdictSummary.append("**最终裁决**: ").append(finalVerdict).append("\n");
        saveToFile(verifyContextPath.resolve("verdict_voting_summary.md").toString(), verdictSummary.toString());
        this.initialAnalysis = verdictSummary.toString();

        // ===== 第四步：基于裁决结果决定后续流程 =====
        LoggerUtil.logVerify(Level.INFO, header, "Final Verdict: " + finalVerdict + " (Score: " + totalScore + ")");
        if ("BUG".equals(finalVerdict)) {
            logWithTestCase("裁决确认是bug，继续进行分析");
            return true;
        } else if("UNCLEAR".equals(finalVerdict)) {
            logWithTestCase("裁决结果不明确，按BUG处理以进行深入分析");
            this.enhanceVerifyFailureReason = "Verdict is UNCLEAR (Score: " + totalScore + "). Proceeding with analysis for safety.";
            return true;
        } else { // TESTCASE_ISSUE
            logWithTestCase("裁决认为是测试用例问题");
            this.enhanceVerifyFailureReason = "Verdict: TESTCASE_ISSUE (Score: " + totalScore + "). The adjudicator determined the issue is with the test case.";
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
            var testcase = this.testCode;
            
            // 生成专门的测试用例问题分析prompt
            // 避免 apiDoc 为 null 导致模板渲染失败
            String apiDoc = testCase.getApiDoc();
            if (apiDoc == null) apiDoc = "";
            String prompt = PromptGen.generateTestCaseIssueExplanationPrompt(testcase, testOutput, apiDoc);
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
    private String performVerdictAnalysis(String bugArgument, String testCaseIssueExplanation) {
        LoggerUtil.logExec(Level.INFO, "执行裁决分析");
        
        try {
            // 准备测试用例代码，添加行号以便LLM分析
            var testcase = this.testCode;
            
            // 生成裁决分析prompt，包含双方论证
            // 避免 apiDoc 为 null
            String apiDoc2 = testCase.getApiDoc();
            if (apiDoc2 == null) apiDoc2 = "";
            String prompt = PromptGen.generateVerdictAnalysisPrompt(testcase, testOutput, apiDoc2, bugArgument, testCaseIssueExplanation);
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
            String fullAnalysis = content + "\n" + verdictCall.arguments.toString() + "\n================================\n";
            if (verifyContextPath != null) {
                appendToFile(verifyContextPath.resolve("verdict_full_analysis.txt").toString(), fullAnalysis);
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
            String response = llm.messageCompletion(prompt, 0.6, true);
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
                apiInfoWithSource,
                getTestCaseIdentifier(),
                GLOBAL_RESULT_DIR,
                GLOBAL_RESULT_TIMESTAMP
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
    @SuppressWarnings("unused")
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
            LoggerUtil.logExec(Level.INFO, "开始消融实验：使用用户指定的无约简/无信息/跳过增强/无审阅配置");
            
            // 构建配置集合（并行执行）
            List<AblationConfig> configs = buildAblationConfigs();
            
            // 并行执行多配置（包含基准+若干单因素消融）
            var manager = ConcurrentExecutionManager.getInstance();
            List<CompletableFuture<AblationResult>> futures = new ArrayList<>();
            
            for (AblationConfig config : configs) {
                CompletableFuture<AblationResult> future = manager.submitLLMTask(() -> {
                    try {
                        String result = generateReportWithConfig(hypotheses, verificationResults, config);
                        LoggerUtil.logExec(Level.INFO, "消融实验配置 " + config.id + " 完成: " + config.name);
                        return new AblationResult(config, result);
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "消融实验配置 " + config.id + " 失败: " + e.getMessage());
                        return new AblationResult(config, 
                            "{\"bug_type\": \"ABLATION_ERROR\", \"report\": \"配置 " + config.id + " (" + config.name + ") 执行失败\"}");
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
            
            // 输出统一 CSV 到 result 目录：ablation_results.csv
            Path ablationResultsCsv = GLOBAL_RESULT_DIR.resolve("ablation_results.csv");
            try {
                boolean exists = Files.exists(ablationResultsCsv);
                if (!exists) {
                    String header = "TestCase,ConfigId,ConfigName,BugType,SkipEnhance,SkipReview,UseMinimized,IncludeInfoSource,IncludeApiDocs,Timestamp\n";
                    appendToFile(ablationResultsCsv.toString(), header);
                }
                String identifier = getTestCaseIdentifier();
                String timestamp = GLOBAL_RESULT_TIMESTAMP;
                ObjectMapper objectMapper = new ObjectMapper();
                for (AblationResult r : results) {
                    String bugType = "UNKNOWN";
                    try {
                        JsonNode node = objectMapper.readTree(r.result);
                        bugType = node.path("bug_type").asText("UNKNOWN");
                    } catch (Exception ignore) {}
                    String csvLine = String.format("\"%s\",%d,\"%s\",%s,%b,%b,%b,%b,%b,%s\n",
                        identifier,
                        r.config.id,
                        r.config.name,
                        bugType,
                        r.config.skipEnhanceVerify,
                        r.config.skipReview,
                        r.config.useMinimizedTestcase,
                        r.config.includeInfoSource,
                        r.config.includeApiDocs,
                        timestamp);
                    appendToFile(ablationResultsCsv.toString(), csvLine);
                }
            } catch (Exception e) {
                logWithTestCase(Level.WARNING, "写入 ablation_results.csv 失败: " + e.getMessage());
            }
            
            // 返回基线配置的结果
            String baseline = results.stream()
                .filter(r -> r.config.id == 0)
                .map(r -> r.result)
                .findFirst()
                .orElse(results.get(0).result);
            return baseline;
        } else {
            // 非消融实验，使用原有配置
            boolean includeInfoSource = edu.tju.ista.llm4test.config.GlobalConfig.isIncludeInfoSource();
            boolean useMinimizedTestcase = edu.tju.ista.llm4test.config.GlobalConfig.isUseMinimizedTestcase();
            boolean includeApiDocs = edu.tju.ista.llm4test.config.GlobalConfig.isIncludeApiDocs();
            
            AblationConfig config = new AblationConfig(includeInfoSource, 
                useMinimizedTestcase, includeApiDocs, false, false, "BASELINE_FULL", 0);
            
            String reportJson = "";
            String feedback = "";
            int REVIEW_ITERATIONS = 3;
            for (int i = 0; i <= REVIEW_ITERATIONS; i++) {
                logWithTestCase("Generating report, iteration " + (i + 1));
                
                reportJson = generateReportWithConfig(hypotheses, verificationResults, config, reportJson, feedback);

                // 如果报告是测试用例错误，则中断审查过程
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode rootNode = objectMapper.readTree(reportJson);
                    String bugType = rootNode.path("bug_type").asText("UNKNOWN");
                    if ("TESTCASE_ERROR".equals(bugType)) {
                        logWithTestCase("在迭代 " + (i + 1) + " 中检测到TESTCASE_ERROR，停止迭代优化。");
                        break;
                    }
                } catch (IOException e) {
                    logWithTestCase(Level.WARNING, "在迭代优化期间解析报告JSON失败: " + e.getMessage());
                }
                
                // After the last iteration, no need to review.
                if (i < REVIEW_ITERATIONS) {
                    logWithTestCase("Reviewing report, iteration " + (i + 1));
                    feedback = reviewReport(reportJson, i + 1);
                    
                    // Save intermediate results
                    if (verifyContextPath != null) {
                        saveToFile(verifyContextPath.resolve("report_iteration_" + (i + 1) + ".json").toString(), reportJson);
                        saveToFile(verifyContextPath.resolve("feedback_iteration_" + (i + 1) + ".txt").toString(), feedback);
                    }
                }
            }
            return reportJson;
        }
    }
    
    /**
     * Reviews a generated bug report using an LLM to provide feedback for improvement.
     * @param reportJson The JSON string of the bug report to review.
     * @param iteration The current iteration number.
     * @return Feedback on the report as a string.
     */
    private String reviewReport(String reportJson, int iteration) {
        logWithTestCase("Starting report review for iteration " + iteration);
        // 注意：review 的跳过由 generateReportWithConfig 的配置层面控制
        if (reportJson == null || reportJson.trim().isEmpty()) {
            logWithTestCase(Level.WARNING, "Report for review is empty or null.");
            return "The previous report was empty. Please generate a complete bug report.";
        }

        if (!isValidJson(reportJson)) {
            logWithTestCase(Level.WARNING, "Invalid JSON report submitted for review.");
            return "The previous report was not valid JSON. Please generate a report in valid JSON format with 'bug_type' and 'report' fields.";
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(reportJson);
            String reportContent = rootNode.path("report").asText("");

            // Build the same information that was used to generate the report
            // StringBuilder hypothesesBuilder = new StringBuilder(); // Empty hypotheses as per current config
            
            // Build verification results (empty for current implementation)
            // StringBuilder resultsBuilder = new StringBuilder();
            
            // Build information source content
            StringBuilder infoSourceBuilder = new StringBuilder();
            if (testCase != null) {
                buildInfoSourceContent(infoSourceBuilder);
                String apiInfoWithSource = testCase.getApiInfoWithSource();
                if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
                    infoSourceBuilder.append("\n# 测试用例API信息和源码\n\n").append(apiInfoWithSource).append("\n");
                }
            }

            // The prompt for the review agent with all the same information used to generate the report
            String prompt = PromptGen.generateBugReportReviewPrompt(
                testCode, 
                testOutput,
                infoSourceBuilder.toString(),
                reportContent
            );
            
            // Log the prompt for debugging
            if (verifyContextPath != null) {
                try {
                    Path promptsDir = verifyContextPath.resolve("prompts");
                    Files.createDirectories(promptsDir);
                    saveToFile(promptsDir.resolve("review_prompt_iteration_" + iteration + ".txt").toString(), prompt);
                } catch (IOException e) {
                    logWithTestCase(Level.WARNING, "Failed to save review prompt: " + e.getMessage());
                }
            }

            // Call LLM for review via toolcall, reusing InformationCollectionAgent's tools
            ArrayList<Tool<?>> tools = new ArrayList<>();
            if (this.infoCollectionAgent != null) {
                Tool<String> st = this.infoCollectionAgent.getSourceTool();
                Tool<String> jt = this.infoCollectionAgent.getJavadocTool();
                if (st != null) tools.add(st);
                if (jt != null) tools.add(jt);
            }

            var reviewResult = llm.toolCallWithContent(prompt, tools);
            appendToFile(verifyContextPath.resolve("review_toolcall.json").toString(), reviewResult.toolCalls().toString());
            String feedback = reviewResult.content();

            // Process any tool calls made during review and add results to collectedInfo
            processReviewToolCalls(reviewResult.toolCalls(), tools);
            
            logWithTestCase("Review feedback received for iteration " + iteration);
            return feedback;

        } catch (TemplateException | IOException e) {
            logWithTestCase(Level.SEVERE, "Error generating review prompt or reviewing report: " + e.getMessage());
            return "Error during review process: " + e.getMessage();
        }
    }
    
    /**
     * Process tool calls made during review and add results to collectedInfo
     */
    private void processReviewToolCalls(List<ToolCall> toolCalls, List<Tool<?>> tools) {
        if (toolCalls == null || toolCalls.isEmpty()) return;

        logWithTestCase("Processing " + toolCalls.size() + " tool calls from review");
        for (ToolCall toolCall : toolCalls) {
            // Find the corresponding tool and execute it
            Tool<String> tool = findToolByName(tools, toolCall.toolName);
            if (tool != null) {
                try {
                    ToolResponse<String> response = tool.execute(toolCall.arguments);
                    if (response.isSuccess()) {
                        addReviewToolResult(response.getResult(), toolCall);
                    }
                } catch (Exception e) {
                    logWithTestCase(Level.WARNING, "Review tool execution failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Find tool by name from the tools list
     */
    @SuppressWarnings("unchecked")
    private Tool<String> findToolByName(List<Tool<?>> tools, String toolName) {
        for (Tool<?> tool : tools) {
            if (tool.getName().equals(toolName)) {
                return (Tool<String>) tool;
            }
        }
        return null;
    }
    
    /**
     * Add review tool call result to collectedInfo (reuse existing format logic)
     */
    private void addReviewToolResult(String content, ToolCall toolCall) {
        if (content == null || content.isEmpty()) return;
        
        String searchType = (String) toolCall.arguments.get("search_type");
        String prefix = toolCall.toolName.contains("SourceCode") ? "SOURCE" : "JAVADOC";
        String title = generateToolTitle(searchType, toolCall.arguments);
        String infoId = "REVIEW_" + prefix + "_" + System.currentTimeMillis();
        
        infoSourceMap.put(infoId, title);
        
        StringBuilder formattedContent = new StringBuilder();
        formattedContent.append("[").append(infoId).append(" 来源: ").append(title).append("]\n");
        formattedContent.append("相关性得分: 0.95\n");
        formattedContent.append("信息类型: ").append(prefix.equals("SOURCE") ? "SOURCE_CODE" : "JAVADOC").append("\n");
        formattedContent.append("内容大小: ").append(content.length()).append(" 字符\n\n");
        formattedContent.append("=== 完整内容 ===\n");
        formattedContent.append(content);
        formattedContent.append("\n=== 内容结束 ===\n");
        
        collectedInfo.put(infoId, formattedContent.toString());
        logWithTestCase("Added review tool result: " + title + " (size: " + content.length() + ")");
    }
    
    /**
     * Generate title for tool results (reuse InformationCollectionAgent logic)
     */
    private String generateToolTitle(String searchType, Map<String, Object> args) {
        if (searchType == null) return "Unknown Search";
        switch (searchType) {
            case "by_class":
                return "Class: " + args.get("class_name");
            case "by_method":
                return "Method: " + args.get("class_name") + "." + args.get("method_name");
            case "by_keyword":
                return "Keyword: " + args.get("keyword");
            case "by_package":
                return "Package: " + args.get("package_name");
            default:
                return "Search: " + searchType;
        }
    }
    
    /**
     * 使用指定配置生成报告
     */
    private String generateReportWithConfig(List<String> hypotheses, Map<String, TestResult> verificationResults, 
                                          AblationConfig config) {
        return generateReportWithConfig(hypotheses, verificationResults, config, "", "");
    }
    
    /**
     * 使用指定配置生成报告
     */
    private String generateReportWithConfig(List<String> hypotheses, Map<String, TestResult> verificationResults, 
                                          AblationConfig config, String previousReport, String feedback) {
        
        // 根据配置选择测试用例
        String actualTestCode = testCode;
        if (!config.useMinimizedTestcase && testCase.getOriginFile() != null) {
            try {
                actualTestCode = Files.readString(testCase.getOriginFile().toPath());
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "读取原始测试用例失败: " + e.getMessage());
            }
        }
        
        // 根据配置选择输出
        String actualTestOutput = testOutput;
        if (!config.useMinimizedTestcase && originalTestOutput != null) {
            actualTestOutput = originalTestOutput;
        }

        // 根据配置跳过增强门槛（如果跳过增强且之前增强失败，也继续）
        if (config.skipEnhanceVerify) {
            writeVerifyStatus("EnhanceVerify", "SKIPPED_CONFIG", config.name);
        } else {
            if (Boolean.FALSE.equals(this.enhancePassed)) {
                // 非跳过增强且增强失败，则直接返回“测试问题”报告
                return "{\"bug_type\": \"TESTCASE_ERROR\", \"report\": \"Enhance verify failed; config requires enhance.\"}";
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
        
        // 构建假设信息：已移除Hypothesis选项，始终不输出hypotheses
        StringBuilder hypothesesBuilder = new StringBuilder(); // Hypotheses disabled
        
        // 构建信息源内容（禁用信息收集时为空字符串）
        StringBuilder infoSourceBuilder = new StringBuilder();
        if ((config.includeInfoSource || config.includeApiDocs)) {
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
                actualTestCode, actualTestOutput, hypothesesBuilder.toString(),
                resultsBuilder.toString(), infoSourceBuilder.toString(),
                // 跳过review时传入空 previousReport/feedback，使其只跑一次
                config.skipReview ? "" : previousReport,
                config.skipReview ? "" : feedback);
            
            // 保存prompt到文件
            savePromptToFile(prompt, config);
            
            String reportJson = OpenAI.ThinkingModel.messageCompletion(prompt, 0.3, true);

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
            return OpenAI.FlashModel.messageCompletion(prompt, 0.3, true);
        } catch (Exception e) {
            logWithTestCase(Level.SEVERE, "使用LLM修复JSON失败: " + e.getMessage());
            return brokenJson; // 返回原始的错误JSON
        }
    }
    
    /**
     * 消融实验配置类
     */
    private static class AblationConfig {
        final boolean includeInfoSource;
        final boolean useMinimizedTestcase;
        final boolean includeApiDocs;
        final boolean skipEnhanceVerify;
        final boolean skipReview;
        final String name;
        final int id;
        
        AblationConfig(boolean includeInfoSource, 
                      boolean useMinimizedTestcase, boolean includeApiDocs,
                      boolean skipEnhanceVerify, boolean skipReview,
                      String name, int id) {
            this.includeInfoSource = includeInfoSource;
            this.useMinimizedTestcase = useMinimizedTestcase;
            this.includeApiDocs = includeApiDocs;
            this.skipEnhanceVerify = skipEnhanceVerify;
            this.skipReview = skipReview;
            this.name = name;
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
     * 构建消融实验配置：
     * - 不约简（useMinimizedTestcase=false）
     * - 不信息收集（includeInfoSource=false）
     * - 跳过增强验证（skipEnhanceVerify=true）
     * - 不审阅（skipReview=true）
     * 额外区分：是否包含API文档（includeApiDocs=true/false）
     */
    private List<AblationConfig> buildAblationConfigs() {
        List<AblationConfig> list = new ArrayList<>();
        // 0. 基线配置：不跳过任何步骤，使用默认全量流程
        list.add(new AblationConfig(
            true,  /* includeInfoSource */
            true,  /* useMinimizedTestcase */
            true,  /* includeApiDocs */
            false, /* skipEnhanceVerify */
            false, /* skipReview */
            "BASELINE_FULL",
            0
        ));
        // 1. 跳过增强验证
        list.add(new AblationConfig(
            true,
            true,
            true,
            true,  /* skipEnhanceVerify */
            false,
            "SKIP_ENHANCE",
            1
        ));
        // 2. 跳过约简（使用原始code/output）
        list.add(new AblationConfig(
            true,
            false, /* useMinimizedTestcase */
            true,
            false,
            false,
            "NO_MINIMIZATION",
            2
        ));
        // 3. 跳过信息收集（空信息源）
        list.add(new AblationConfig(
            false, /* includeInfoSource */
            true,
            true,
            false,
            false,
            "NO_INFO",
            3
        ));
        // 4. 跳过review：单次生成，不做迭代
        list.add(new AblationConfig(
            true,
            true,
            true,
            false,
            true,  /* skipReview */
            "SKIP_REVIEW",
            4
        ));
        return list;
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
                String fileName = "ablation_config_" + result.config.id + "_" + sanitizeFilePart(result.config.name) + ".json";
                saveToFile(ablationDir.resolve(fileName).toString(), result.result);
            }
            
            // 保存对比摘要
            StringBuilder summary = new StringBuilder();
            summary.append("# 消融实验结果摘要\n\n");
            summary.append("共执行了 ").append(results.size()).append(" 个配置组合\n\n");
            
            for (AblationResult result : results) {
                summary.append("## 配置 ").append(result.config.id).append(" - ").append(result.config.name).append("\n");
                summary.append("- 包含信息源: ").append(result.config.includeInfoSource).append("\n");
                summary.append("- 使用最小化测试用例: ").append(result.config.useMinimizedTestcase).append("\n");
                summary.append("- 包含API文档: ").append(result.config.includeApiDocs).append("\n\n");
                summary.append("- 跳过增强验证: ").append(result.config.skipEnhanceVerify).append("\n");
                summary.append("- 跳过报告审阅: ").append(result.config.skipReview).append("\n\n");
            }
            
            saveToFile(ablationDir.resolve("ablation_summary.md").toString(), summary.toString());
            
            // 保存CSV格式的结果
            saveAblationResultsToCSV(results, ablationDir);
            
            LoggerUtil.logExec(Level.INFO, "已保存消融实验结果到: " + ablationDir);
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存消融实验结果失败: " + e.getMessage());
        }
    }

    private static String sanitizeFilePart(String name) {
        if (name == null) return "noname";
        return name.replaceAll("[^a-zA-Z0-9-_]", "_");
    }
    
    /**
     * 将消融实验结果保存为CSV格式
     */
    private void saveAblationResultsToCSV(List<AblationResult> results, Path ablationDir) {
        try {
            Path csvFile = ablationDir.resolve("ablation.csv");
            StringBuilder csvContent = new StringBuilder();
            
            // CSV表头
            csvContent.append("config_id,test_case,bug_type,include_info_source,use_minimized_testcase,include_api_docs,timestamp\n");
            
            String testCaseName = getTestCaseIdentifier();
            String timestamp = String.valueOf(System.currentTimeMillis());
            
            for (AblationResult result : results) {
                String bugType = "UNKNOWN";
                try {
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode rootNode = objectMapper.readTree(result.result);
                    bugType = rootNode.path("bug_type").asText("UNKNOWN");
                } catch (IOException e) {
                    LoggerUtil.logExec(Level.WARNING, "解析消融实验结果JSON失败: " + e.getMessage());
                }
                
                csvContent.append(result.config.id).append(",")
                         .append(testCaseName).append(",")
                         .append(bugType).append(",")
                         .append(result.config.includeInfoSource).append(",")
                         .append(result.config.useMinimizedTestcase).append(",")
                         .append(result.config.includeApiDocs).append(",")
                         .append(timestamp).append("\n");
            }
            
            saveToFile(csvFile.toString(), csvContent.toString());
            LoggerUtil.logExec(Level.INFO, "已保存消融实验CSV结果到: " + csvFile);
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "保存消融实验CSV结果失败: " + e.getMessage());
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
        
        // 在批处理开始时，一次性加载约简名单
        loadManifestIfNeeded();
        
        Path sharedVerifyDir = null;
        try {
            // 1. 在批处理开始前，创建一次共享的验证环境
            sharedVerifyDir = setupSharedVerifyEnvironment();
            if (sharedVerifyDir == null) {
                LoggerUtil.logExec(Level.SEVERE, "无法创建共享验证环境，中止任务。");
                return;
            }

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
            final Path finalSharedVerifyDir = sharedVerifyDir;
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
                        testcase.recalculateApiDocs();

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
                        
                        // 2. 将共享目录路径设置给 agent
                        agent.setSharedVerifyDir(finalSharedVerifyDir);
                        agent.setTestCase(testcase);


                        agent.analyze();

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
        } finally {
            // 3. 在所有操作完成后，清理共享环境
            if (sharedVerifyDir != null) {
                cleanupSharedVerifyEnvironment(sharedVerifyDir);
            }
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

            boolean isBug;
            if (edu.tju.ista.llm4test.config.GlobalConfig.isEnableAblationTest()) {
                logWithTestCase("Ablation mode: skipping enhanceVerify in verifyBugFromFile.");
                writeVerifyStatus("EnhanceVerify", "SKIPPED", "Ablation mode (verifyBugFromFile)");
                isBug = true;
            } else {
                isBug = this.enhanceVerify();
            }
            if (isBug) {
                LoggerUtil.logVerify(Level.INFO, "Test case is a confirmed bug, proceeding to analysis: " + testCaseName);
                this.analyze();
            } else {
                LoggerUtil.logVerify(Level.INFO, "Test case is not a bug: " + testCaseName);
                this.generateNonBugReport(); // 为非bug情况生成报告
            }
            
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


    private static void cleanupSharedVerifyEnvironment(Path sharedVerifyDir) {
        if (sharedVerifyDir != null && Files.exists(sharedVerifyDir)) {
            try {
                FileUtils.deleteDirectory(sharedVerifyDir.toFile());
                LoggerUtil.logExec(Level.INFO, "Shared verify environment cleaned up: " + sharedVerifyDir);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.SEVERE, "Failed to clean up shared verify environment: " + e.getMessage());
            }
        }
    }

    /**
     * 设置共享的验证环境：拷贝整个test目录到verify目录
     * @return verify目录路径，失败时返回null
     */
    private static Path setupSharedVerifyEnvironment() {
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
            Path targetVerifyDir = verifyRootDir.resolve("test_shared_" + timestamp);
            
            // 复制整个test目录
            LoggerUtil.logExec(Level.INFO, "Copying shared test environment: " + sourceTestDir + " -> " + targetVerifyDir);
            FileUtils.copyDirectory(sourceTestDir.toFile(), targetVerifyDir.toFile());
            
            LoggerUtil.logExec(Level.INFO, "Shared verify environment setup complete: " + targetVerifyDir);
            return targetVerifyDir;
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to setup shared verify environment: " + e.getMessage());
            return null;
        }
    }

    /**
     * 在共享目录中准备测试用例，将其恢复到原始状态。
     * @param originalTestCase 原始测试用例
     * @param verifyDir verify目录路径
     * @return 更新后的测试用例，失败时返回null
     */
    private TestCase prepareTestCaseInSharedDir(TestCase originalTestCase, Path verifyDir) {
        try {
            // 计算相对路径：从test目录到具体测试文件
            Path testDir = Paths.get(GlobalConfig.getTestDir()).toAbsolutePath();
            
            // 修复：优先使用原始文件路径，避免验证目录路径计算问题
            Path originalPath;
            if (originalTestCase.getOriginFile() != null) {
                originalPath = originalTestCase.getOriginFile().toPath().toAbsolutePath();
            } else {
                Path currentPath = originalTestCase.getFile().toPath().toAbsolutePath();
                // 如果当前文件在验证目录中，无法计算正确的相对路径，返回null
                if (currentPath.toString().contains("/verify/")) {
                    logWithTestCase(Level.SEVERE, "prepareTestCaseInSharedDir: 文件在验证目录中且无原始文件引用，无法准备");
                    return null;
                }
                originalPath = currentPath;
            }
            
            Path relativePath;
            try {
                relativePath = testDir.relativize(originalPath).normalize();
            } catch (IllegalArgumentException iae) {
                logWithTestCase(Level.SEVERE, "prepareTestCaseInSharedDir 相对路径计算失败: testDir=" + testDir + " original=" + originalPath + " — " + iae.getMessage());
                return null;
            }

            // 在verify目录中的新路径并规范化
            Path newTestPath = verifyDir.resolve(relativePath).normalize();
            
            // 安全检查：确保新路径在验证目录内
            if (!newTestPath.startsWith(verifyDir)) {
                logWithTestCase(Level.SEVERE, "prepareTestCaseInSharedDir: 计算的路径不在验证目录内: " + newTestPath);
                return null;
            }
            
            // 关键步骤：用原始文件内容覆盖共享目录中的同名文件，以重置状态
            String originalContent = Files.readString(originalTestCase.getFile().toPath());
            Files.writeString(newTestPath, originalContent);
            
            // 创建新的TestCase对象
            TestCase verifyTestCase = new TestCase(newTestPath.toFile());
            verifyTestCase.setOriginFile(originalTestCase.getFile()); // 保持原始文件引用
            verifyTestCase.setResult(originalTestCase.getResult());    // 保持测试结果
            verifyTestCase.verifyMessage = originalTestCase.verifyMessage; // 保持验证信息
            
            // 设置API文档处理器（使用配置创建新的处理器以保证一致性）
            verifyTestCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());
            
            logWithTestCase(Level.INFO, "Test case prepared in shared verify environment: " + newTestPath);
            return verifyTestCase;
            
        } catch (Exception e) {
            logWithTestCase(Level.SEVERE, "Failed to prepare test case in shared dir: " + e.getMessage());
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

    /**
     * 写入约简统计到 minimization_status.csv。
     */
    private void writeMinimizationStatus(boolean success, int originalLength, int minimizedLength) {
        try {
            Path miniCsv = GLOBAL_RESULT_DIR.resolve("minimization_status.csv");
            if (!Files.exists(miniCsv)) {
                saveToFile(miniCsv.toString(), "TestCase,Success,OriginalLength,MinimizedLength,Reduction,ReductionPercentage,Timestamp\n");
            }
            int reduction = originalLength - minimizedLength;
            double reductionPercentage = originalLength > 0 ? (double) reduction / originalLength * 100 : 0;
            String csvLine = String.format("%s,%b,%d,%d,%d,%.2f%%,%s\n",
                getTestCaseIdentifier(), success, originalLength, minimizedLength, reduction, reductionPercentage, GLOBAL_RESULT_TIMESTAMP);
            appendToFile(miniCsv.toString(), csvLine);
        } catch (Exception e) {
            logWithTestCase(Level.WARNING, "写入约简CSV失败: " + e.getMessage());
        }
    }

    /**
     * 将最小化后的当前 testCase 文件缓存到 ReduceResult，并更新 reduced_list.txt 与内存清单。
     */
    private void cacheMinimizedFileAndUpdateManifest() {
        synchronized (REDUCE_CACHE_FS_LOCK) {
            try {
            Path testDir = Paths.get(GlobalConfig.getTestDir()).toAbsolutePath();
            // 关键修复：始终使用 originFile 的路径来计算缓存位置，而不是当前验证目录中的文件路径
            Path originalPath = this.testCase.getOriginFile() != null ? 
                this.testCase.getOriginFile().toPath().toAbsolutePath() : 
                this.testCase.getFile().toPath().toAbsolutePath();
            
            // 如果 originalPath 不在 testDir 下（比如在 verify 目录中），跳过缓存
            if (this.testCase.getOriginFile() == null && this.testCase.getFile().toPath().toAbsolutePath().toString().contains("/verify/")) {
                logWithTestCase(Level.SEVERE, "跳过缓存：测试文件在验证目录中，无法确定原始路径");
                return;
            }
                Path relativePath;
                try {
                    relativePath = testDir.relativize(originalPath).normalize();
                } catch (IllegalArgumentException iae) {
                    logWithTestCase(Level.SEVERE, "缓存写入时相对路径计算失败: testDir=" + testDir + " original=" + originalPath + " — " + iae.getMessage());
                    return;
                }

                // 仅拷贝约简后的文件
                Path sourceFile = this.testCase.getFile().toPath();
                Path destFile = REDUCE_CACHE_DIR.resolve(relativePath);
                
                Files.createDirectories(destFile.getParent());
                Files.copy(sourceFile, destFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logWithTestCase(Level.INFO, "约简结果已缓存: " + destFile);

                // 名单追加（内存与文件）
                String normalizedPath = relativePath.toString().replace(java.io.File.separator, "/");
                if (!reducedTestManifest.contains(normalizedPath)) {
                    Files.createDirectories(REDUCE_CACHE_DIR);
                    appendToFile(REDUCE_MANIFEST.toString(), normalizedPath + "\n");
                    reducedTestManifest.add(normalizedPath);
                }
            } catch (Exception cacheEx) {
                logWithTestCase(Level.WARNING, "缓存 ReduceResult 失败: " + cacheEx.getMessage());
            }
        }
    }

    /**
     * Writes a status update to the verify_status.csv file.
     * This method is synchronized to handle concurrent writes from multiple threads.
     * @param step The current step in the verification process.
     * @param status The status of the step.
     * @param details Additional details.
     */
    private synchronized void writeVerifyStatus(String step, String status, String details) {
        try {
            Path statusCsv = GLOBAL_RESULT_DIR.resolve("verify_status.csv");
            if (!Files.exists(statusCsv)) {
                appendToFile(statusCsv.toString(), "TestCase,Timestamp,Step,Status,Details\n");
            }
            String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                getTestCaseIdentifier(),
                GLOBAL_RESULT_TIMESTAMP,
                step,
                status,
                details.replace("\"", "'") // simple CSV escape for quotes
            );
            appendToFile(statusCsv.toString(), line);
        } catch (Exception e) {
            logWithTestCase(Level.WARNING, "Failed to write to verify_status.csv: " + e.getMessage());
        }
    }
}
