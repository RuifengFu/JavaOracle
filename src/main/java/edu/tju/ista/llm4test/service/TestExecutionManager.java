package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.ApplicationConfig;
import edu.tju.ista.llm4test.execute.*;
import edu.tju.ista.llm4test.utils.*;

import java.io.File;
import java.nio.file.Files;
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
    private final File resultDir;

    public TestExecutionManager(String jarPath, File resultDir, String baseDocPath, String suitePath) {
        this.testExecutor = new TestExecutor(resultDir);
        this.statistics = new TestStatistics();
        // 使用配置文件创建支持多模块的ApiDocProcessor
        this.apiInfoProcessor = ApiInfoProcessor.fromConfig();
        this.fileProcessor = new FileProcessor(resultDir);
        this.resultDir = resultDir;
        this.testSuite = new TestSuite(suitePath);
        
        // 复制测试文件
        fileProcessor.copyTestFiles(Path.of(ApplicationConfig.getJdkTestPath()));
    }

    /**
     * 并行执行测试套件（生成模式）
     * 两阶段处理：
     * 1. 执行所有测试，筛选出成功的测试用例
     * 2. 对成功的测试用例进行enhance、verify和fix
     */
    public void runTestSuiteParallel() {
        List<File> files = getTestFiles();
        
        System.out.println("开始生成模式，总文件数: " + files.size());
        
        // ========== 第一阶段：执行测试并筛选成功的用例 ==========
        List<TestCase> successfulTestCases = filterSuccessfulTestCases(files);
        
        if (successfulTestCases.isEmpty()) {
            System.out.println("没有成功的测试用例，生成模式结束");
            return;
        }
        
        // ========== 第二阶段：对成功的测试用例进行enhance、verify和fix ==========
        System.out.println("第二阶段：对 " + successfulTestCases.size() + " 个成功测试用例进行enhance、verify和fix...");
        
        CompletableFuture<Void> phase2Tasks = CompletableFuture.allOf(
                successfulTestCases.stream()
                        .map(testCase -> processSuccessfulTestCase(testCase, testExecutor)
                                .handle((result, throwable) -> {
                                    if (throwable != null) {
                                        LoggerUtil.logExec(Level.WARNING, "处理成功测试用例失败: " + testCase.getFile() + "\n" + throwable.getMessage());
                                        // 如果处理失败，保持原来的成功状态
                                    }
                                    // 记录最终结果
                                    statistics.recordResult(testCase.getResult().getKind());
                                    LoggerUtil.logResult(Level.INFO, testCase.getFile() + " " + testCase.getResult().getKind());
                                    if (testCase.getResult().isFail() || testCase.getResult().isBug()) {
                                        LoggerUtil.logResult(Level.INFO, testCase.verifyMessage);
                                    }
                                    return null;
                                }))
                        .toArray(CompletableFuture[]::new));
        
        try {
            phase2Tasks.join();
            LoggerUtil.logExec(Level.INFO, "第二阶段处理完成");
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "第二阶段处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("生成模式完成");
        
        // 记录统计信息并清理
        statistics.logStatistics();
        testExecutor.clearTempDirectories();
    }

    /**
     * 并行执行测试套件（执行模式）
     * 使用测试线程池进行测试执行（CPU密集型）
     */
    public void runTestSuiteParallelExecution(Path rootPath) {
        List<File> files = getTestFiles();
        
        System.out.println("开始执行模式，总文件数: " + files.size());
        
        // 创建TestCase并启动异步执行任务（使用测试线程池）
        List<CompletableFuture<TestResult>> futures = files.stream()
                .filter(this::isValidTestFile)
                .map(this::createTestCaseForExecution)
                .map(testCase -> testCase.executeTestAsync(testExecutor)
                        .handle((result, throwable) -> {
                            if (throwable != null) {
                                LoggerUtil.logExec(Level.WARNING, "执行测试用例失败: " + testCase.getFile() + "\n" + throwable.getMessage());
                                statistics.recordResult(TestResultKind.UNKNOWN);
                                return new TestResult(TestResultKind.UNKNOWN);
                            } else {
                                statistics.recordResult(result.getKind());
                                LoggerUtil.logResult(Level.INFO, testCase.getFile() + " " + result.getKind() + 
                                    (result.getJtregResult() != null ? " " + result.getJtregResult().exitValue : ""));
                                return result;
                            }
                        }))
                .collect(Collectors.toList());
        
        // 等待所有执行任务完成
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        
        try {
            allTasks.join();
            LoggerUtil.logExec(Level.INFO, "所有测试执行任务完成");
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "测试执行过程中发生不可恢复的错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 记录统计信息
        statistics.logStatistics();
    }
    
    /**
     * 获取测试文件列表
     */
    private List<File> getTestFiles() {
        return testSuite.getTestCases().stream()
                .map(s -> ApplicationConfig.getJdkTestPath() + "/jdk/" + s)
                .map(File::new)
                .collect(Collectors.toList());
    }
    
    /**
     * 检查文件是否有效
     */
    private boolean isValidTestFile(File file) {
        try {
            return file.exists() && Files.size(file.toPath()) <= ApplicationConfig.getMaxFileSize();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "检查文件失败: " + file + "\n" + e.getMessage());
            return false;
        }
    }

    /**
     * 为生成模式创建TestCase
     */
    private TestCase createTestCaseForGeneration(File originFile) {
        File targetFile = createTargetFile(originFile);
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
        File targetFile = createTargetFile(originFile);
        TestCase testCase = new TestCase(targetFile);
        testCase.setOriginFile(originFile);
        
        return testCase;
    }

    /**
     * 创建目标文件
     */
    private File createTargetFile(File originFile) {
        return new File(originFile.getAbsolutePath().replace(ApplicationConfig.getJdkTestPath(), ApplicationConfig.getTestDir()));
    }
    
    /**
     * 处理成功的测试用例：enhance、verify和fix
     * @param testCase 成功的测试用例
     * @param testExecutor 测试执行器
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> processSuccessfulTestCase(TestCase testCase, TestExecutor testExecutor) {
        // 直接使用TestCase的增强工作流程方法，避免重复实现逻辑
        return testCase.processEnhancementWorkflowAsync(testExecutor);
    }
    
    /**
     * 过滤出执行成功的测试用例
     * @param files 测试文件列表
     * @return 成功的测试用例列表
     */
    private List<TestCase> filterSuccessfulTestCases(List<File> files) {
        System.out.println("第一阶段：执行所有测试用例并过滤成功的用例...");
        
        List<TestCase> allTestCases = files.stream()
                .filter(this::isValidTestFile)
                .map(this::createTestCaseForGeneration)
                .collect(Collectors.toList());
        
        // 并行执行所有测试用例
        CompletableFuture<Void> executionTasks = CompletableFuture.allOf(
                allTestCases.stream()
                        .map(testCase -> testCase.executeTestAsync(testExecutor)
                                .handle((result, throwable) -> {
                                    if (throwable != null) {
                                        LoggerUtil.logExec(Level.WARNING, "测试执行失败: " + testCase.getFile() + "\n" + throwable.getMessage());
                                        testCase.setResult(new TestResult(TestResultKind.UNKNOWN));
                                    } else {
                                        testCase.setResult(result);
                                        LoggerUtil.logResult(Level.INFO, "初始测试: " + testCase.getFile() + " " + result.getKind());
                                    }
                                    return null;
                                }))
                        .toArray(CompletableFuture[]::new));
        
        try {
            executionTasks.join();
            LoggerUtil.logExec(Level.INFO, "第一阶段测试执行完成");
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "第一阶段测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 过滤出成功的测试用例，失败的直接丢弃（不统计）
        List<TestCase> successfulTestCases = allTestCases.stream()
                .filter(testCase -> testCase.getResult().isSuccess())
                .collect(Collectors.toList());
        
        int totalCount = allTestCases.size();
        int successCount = successfulTestCases.size();
        int failureCount = totalCount - successCount;
        
        System.out.println(String.format("第一阶段过滤完成 - 总计: %d, 成功: %d, 失败: %d (失败的已丢弃)", 
                totalCount, successCount, failureCount));
        
        LoggerUtil.logExec(Level.INFO, String.format("成功测试用例过滤完成，保留 %d/%d 个测试用例进行后续处理", 
                successCount, totalCount));
        
        return successfulTestCases;
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