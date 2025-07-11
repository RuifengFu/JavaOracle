package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.*;
import edu.tju.ista.llm4test.utils.*;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
     * 2. 对成功的测试用例进行enhance、verify和fix
     */
    public void runTestSuiteParallel() {
        System.out.println("开始生成模式，缓存模式: " + (GlobalConfig.isUseCacheMode() ? "开启" : "关闭"));
        
        // 第一阶段：筛选成功的测试用例
        List<TestCase> successfulTestCases = testSuite.filterSuccessfulTestCases(
                this::createTestCaseForGeneration, testExecutor);
        
        if (successfulTestCases.isEmpty()) {
            System.out.println("没有成功的测试用例，生成模式结束");
            return;
        }
        
        // 第二阶段：处理测试用例
        System.out.println("开始处理 " + successfulTestCases.size() + " 个测试用例...");
        processTestCases(successfulTestCases);
        
        System.out.println("生成模式完成");
        statistics.logStatistics();
        testExecutor.clearTempDirectories();
    }

    /**
     * 并行执行测试套件（执行模式）
     * 使用测试线程池进行测试执行（CPU密集型）
     */
    public void runTestSuiteParallelExecution(Path rootPath) {
        System.out.println("开始执行模式");
        
        // 创建并执行所有测试用例
        List<TestCase> testCases = createExecutionTestCases();
        System.out.println("总文件数: " + testCases.size());
        
        // 执行测试并记录结果
        try {
            testSuite.executeTestCasesAsync(testCases, testExecutor)
                    .whenComplete((result, throwable) -> testCases.forEach(this::recordTestResult))
                    .join();
            LoggerUtil.logExec(Level.INFO, "所有测试执行任务完成");
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "测试执行过程中发生不可恢复的错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        statistics.logStatistics();
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