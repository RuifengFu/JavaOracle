package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.*;
import edu.tju.ista.llm4test.llm.TokenUsageTracker;
import edu.tju.ista.llm4test.utils.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 测试执行管理器
 * 负责管理测试用例的并行执行
 * 委托具体任务给TestCase处理，TestCase会使用合适的线程池
 */
public class TestExecutionManager {
    
    private final TestExecutor testExecutor;
    private final TestStatistics statistics;
    private final ApiInfoProcessor apiInfoProcessor;
    private final FileProcessor fileProcessor;
    private final TestSuite testSuite;


    public TestExecutionManager(String jarPath, File resultDir, String baseDocPath, String suitePath) {
        this.testExecutor = new TestExecutor();
        this.statistics = new TestStatistics();
        // 使用配置文件创建支持多模块的ApiDocProcessor
        this.apiInfoProcessor = ApiInfoProcessor.fromConfig();
        this.fileProcessor = new FileProcessor(resultDir);
        this.testSuite = new TestSuite(suitePath);
        
        // 复制测试文件
        fileProcessor.copyTestFiles(Path.of(GlobalConfig.getJdkTestPath()));
    }

    /**
     * 并行执行测试套件（生成模式）
     * 两阶段处理：
     * 1. 执行所有测试，筛选出成功的测试用例（支持缓存模式）
     * 2. 对成功的测试用例进行enhance、verify和fix（默认流水线模式）
     */
    public void runTestSuiteParallel() {
        TokenUsageTracker.getInstance().reset();
        System.out.println("开始生成模式，缓存模式: " + (GlobalConfig.isUseCacheMode() ? "开启" : "关闭"));
        
        // 第一阶段：筛选成功的测试用例
        List<TestCase> successfulTestCases = testSuite.filterSuccessfulTestCases(
                this::createTestCaseForGeneration, testExecutor);
        
        if (successfulTestCases.isEmpty()) {
            System.out.println("没有成功的测试用例，生成模式结束");
            TokenUsageTracker.getInstance().logSummary(0);
            return;
        }

        DebugUtils.getInstance().createPart("VerifyFail");
        // 第二阶段：处理测试用例
        System.out.println("开始处理 " + successfulTestCases.size() + " 个测试用例...");
        processTestCases(successfulTestCases);
        
        System.out.println("生成模式完成");
        statistics.logStatistics();
        TokenUsageTracker.getInstance().logSummary(successfulTestCases.size());
        testExecutor.clearTempDirectories();
    }

    /**
     * 并行执行测试套件（执行模式）
     * 支持断点续传：加载已有缓存跳过已完成的用例，每个用例完成后立即保存
     */
    public void runTestSuiteParallelExecution() {
        TokenUsageTracker.getInstance().reset();
        System.out.println("开始执行模式");

        Set<String> cachedPaths = testSuite.loadCachedTestCasePaths();
        List<TestCase> allTestCases = createExecutionTestCases();

        List<TestCase> remainingTestCases;
        if (cachedPaths.isEmpty()) {
            remainingTestCases = allTestCases;
        } else {
            String jdkTestPath = GlobalConfig.getJdkTestPath() + "/jdk/";
            Path jdkTestRoot = Paths.get(jdkTestPath).toAbsolutePath().normalize();
            remainingTestCases = allTestCases.stream()
                    .filter(tc -> {
                        try {
                            String relPath = jdkTestRoot.relativize(tc.getOriginFile().toPath().toAbsolutePath().normalize())
                                    .toString().replace('\\', '/');
                            boolean skip = cachedPaths.contains(relPath);
                            if (skip) {
                                LoggerUtil.logExec(Level.FINE, "跳过已缓存用例: " + relPath);
                            }
                            return !skip;
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .collect(Collectors.toList());
            LoggerUtil.logExec(Level.INFO, String.format("断点续传: 总计 %d, 已缓存 %d, 剩余 %d",
                    allTestCases.size(), cachedPaths.size(), remainingTestCases.size()));
            System.out.println("断点续传: 总计 " + allTestCases.size() +
                    ", 已缓存 " + cachedPaths.size() +
                    ", 剩余 " + remainingTestCases.size());
        }

        if (remainingTestCases.isEmpty()) {
            System.out.println("所有测试用例已缓存，无需重新执行");
            statistics.logStatistics();
            return;
        }

        System.out.println("待执行文件数: " + remainingTestCases.size());

        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            CompletableFuture<Void> execution = CompletableFuture.allOf(
                    remainingTestCases.stream()
                            .map(testCase -> testCase.executeTestAsync(testExecutor)
                                    .handle((result, throwable) -> {
                                        if (throwable != null) {
                                            LoggerUtil.logExec(Level.WARNING, "测试执行失败: " + testCase.getFile() + "\n" + throwable.getMessage());
                                            testCase.setResult(new TestResult(TestResultKind.TEST_FAIL));
                                        } else {
                                            testCase.setResult(result);
                                            LoggerUtil.logResult(Level.INFO, "测试执行: " + testCase.getFile() + " " + result.getKind());
                                            if (result.isSuccess()) {
                                                testSuite.appendTestCaseToCache(testCase);
                                                successCount.incrementAndGet();
                                            }
                                        }
                                        recordTestResult(testCase);
                                        int completed = completedCount.incrementAndGet();
                                        if (completed % 50 == 0) {
                                            System.out.println("进度: " + completed + "/" + remainingTestCases.size() +
                                                    " (成功: " + successCount.get() + ")");
                                        }
                                        return null;
                                    }))
                            .toArray(CompletableFuture[]::new));

            execution.join();
            LoggerUtil.logExec(Level.INFO, "所有测试执行任务完成");
            System.out.println("执行完成: " + successCount.get() + "/" + remainingTestCases.size() + " 通过");
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "测试执行过程中发生不可恢复的错误: " + e.getMessage());
            e.printStackTrace();
        }

        List<TestCase> newlySuccessful = remainingTestCases.stream()
                .filter(tc -> tc.getResult() != null && tc.getResult().isSuccess())
                .collect(Collectors.toList());
        testSuite.deduplicateAndSaveCache(newlySuccessful);

        statistics.logStatistics();
        TokenUsageTracker.getInstance().logSummary(remainingTestCases.size());
    }

    /**
     * 创建执行模式的测试用例列表
     */
    private List<TestCase> createExecutionTestCases() {
        return testSuite.getTestFiles().stream()
                .filter(testSuite::isValidTestFile)
                .map(this::createTestCaseForExecution)
                .collect(Collectors.toList());
    }
    
    /**
     * 记录单个测试用例的结果
     */
    private void recordTestResult(TestCase testCase) {
        TestResultKind kind = testCase.getResult() != null ? 
                testCase.getResult().getKind() : TestResultKind.UNKNOWN;
        if (testCase.getResult() == null){
            statistics.recordResult(TestResultKind.UNKNOWN);
        } else {
            statistics.recordResult(testCase.getResult());
        }
        
        String logMessage = testCase.getFile() + " " + kind;
        if (testCase.getResult() != null && testCase.getResult().getCompilationFailed()) {
            logMessage += " COMPILE_FAILED";
        }
        if (testCase.getResult() != null && testCase.getResult().getJtregResult() != null) {
            logMessage += " " + testCase.getResult().getJtregResult().exitValue;
        }
        LoggerUtil.logResult(Level.INFO, logMessage);
        
        // 对于失败或bug的情况，额外记录验证信息
        if (testCase.getResult() != null && (testCase.getResult().isFail() || testCase.getResult().isBug()) && testCase.verifyMessage != null) {
            LoggerUtil.logResult(Level.INFO, testCase.verifyMessage);
        }
    }

    /**
     * 为生成模式创建TestCase
     */
    private TestCase createTestCaseForGeneration(File originFile) {
        File targetFile = getTargetFilePath(originFile);
        TestCase testCase = new TestCase(targetFile);
        testCase.removeHeader();
        testCase.setOriginFile(originFile);
        
        // 设置API文档处理器，支持后续重新计算
        testCase.setApiDocProcessor(apiInfoProcessor);
        
        // 设置API文档
        Map<String, String> apiDocs = new HashMap<>();
        try {
            apiDocs = apiInfoProcessor.processApiDocs(originFile);
        } catch (Exception e){
            LoggerUtil.logExec(Level.WARNING, "处理API文档失败: " + originFile + "\n" + e.getMessage());
        }

        testCase.setApiDocMap(apiDocs);
        
        return testCase;
    }
    
    /**
     * 为执行模式创建TestCase
     */
    private TestCase createTestCaseForExecution(File originFile) {
        File targetFile = getTargetFilePath(originFile);
        TestCase testCase = new TestCase(targetFile);
        testCase.setOriginFile(originFile);
        
        return testCase;
    }

    /**
     * 将源文件路径转换为目标测试目录中的对应路径
     * 例如：jdk17u-dev/test/java/lang/String/Test.java -> test/java/lang/String/Test.java
     */
    private File getTargetFilePath(File originFile) {
        return new File(originFile.getAbsolutePath().replace(GlobalConfig.getJdkTestPath(), GlobalConfig.getTestDir()));
    }
    
    /**
     * 处理测试用例（enhance、verify和fix）
     */
    private void processTestCases(List<TestCase> testCases) {
        try {
            if (GlobalConfig.isLegacyEnhanceThenVerifyWorkflow()) {
                processTestCasesLegacyTwoStage(testCases);
                return;
            }
            CompletableFuture<?>[] tasks = testCases.stream()
                    .map(testCase -> testCase.processEnhancementWorkflowAsync(testExecutor)
                            .handle((result, throwable) -> {
                                handleTestCaseResult(testCase, throwable);
                                return null;
                            }))
                    .toArray(CompletableFuture[]::new);
            
            CompletableFuture.allOf(tasks).join();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processTestCasesLegacyTwoStage(List<TestCase> testCases) {
        LoggerUtil.logExec(Level.INFO, "使用旧工作流：先增强全部测试，再统一验证/修复");

        CompletableFuture<?>[] enhanceTasks = testCases.stream()
                .map(testCase -> testCase.enhanceAsync()
                        .handle((result, throwable) -> {
                            if (throwable != null) {
                                LoggerUtil.logExec(Level.WARNING, "增强测试用例失败: " + testCase.getFile() + " - " + throwable.getMessage());
                            }
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(enhanceTasks).join();

        CompletableFuture<?>[] verifyTasks = testCases.stream()
                .map(testCase -> testCase.processPostEnhancementWorkflowAsync(testExecutor)
                        .handle((result, throwable) -> {
                            handleTestCaseResult(testCase, throwable);
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(verifyTasks).join();
    }
    
    /**
     * 处理测试用例结果
     */
    private void handleTestCaseResult(TestCase testCase, Throwable throwable) {
        if (throwable != null) {
            LoggerUtil.logExec(Level.WARNING, 
                "处理测试用例失败: " + testCase.getFile() + " - " + throwable.getMessage());
        }
        recordTestResult(testCase);
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        if (testExecutor != null) {
            testExecutor.shutdown();
        }
        // ConcurrentExecutionManager是单例，不在这里关闭
        // 它会在应用程序关闭时通过shutdown hook自动关闭
    }
}
