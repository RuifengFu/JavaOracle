package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.PathUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class TestSuite {

    private final String rootPath;
    private final ArrayList<String> testCases;

    public TestSuite(String rootPath) {
        this.rootPath = rootPath;
        this.testCases = jtregTestSuiteFinder();
    }

    public ArrayList<String> jtregTestSuiteFinder() {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("jtreg", "-l", rootPath);
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String[] lines = stdout.split("\n");
            var list = Arrays.asList(lines).subList(1, lines.length - 1).stream().filter(s -> s.endsWith(".java")).collect(Collectors.toCollection(ArrayList::new));
            return list;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取测试文件列表
     */
    public List<File> getTestFiles() {
        return testCases.stream()
                .map(s -> GlobalConfig.getJdkTestPath() + "/jdk/" + s)
                .map(File::new)
                .collect(Collectors.toList());
    }
    
    /**
     * 检查文件是否有效
     */
    public boolean isValidTestFile(File file) {
        try {
            return file.exists() && Files.size(file.toPath()) <= GlobalConfig.getMaxFileSize();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "检查文件失败: " + file + "\n" + e.getMessage());
            return false;
        }
    }
    
    /**
     * 并行执行测试用例
     * @param testCases 测试用例列表
     * @param testExecutor 测试执行器
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> executeTestCasesAsync(List<TestCase> testCases, TestExecutor testExecutor) {
        LoggerUtil.logExec(Level.INFO, "开始执行 " + testCases.size() + " 个测试用例");
        
        CompletableFuture<Void> executionTasks = CompletableFuture.allOf(
                testCases.stream()
                        .map(testCase -> testCase.executeTestAsync(testExecutor)
                                .handle((result, throwable) -> {
                                    if (throwable != null) {
                                        LoggerUtil.logExec(Level.WARNING, "测试执行失败: " + testCase.getFile() + "\n" + throwable.getMessage());
                                        testCase.setResult(new TestResult(TestResultKind.TEST_FAIL));
                                    } else {
                                        testCase.setResult(result);
                                        LoggerUtil.logResult(Level.INFO, "测试执行: " + testCase.getFile() + " " + result.getKind());
                                    }
                                    return null;
                                }))
                        .toArray(CompletableFuture[]::new));
        
        return executionTasks;
    }
    
    /**
     * 过滤出执行成功的测试用例，支持缓存模式
     * @param testCaseFactory 测试用例创建工厂函数
     * @param testExecutor 测试执行器
     * @return 成功的测试用例列表
     */
    public List<TestCase> filterSuccessfulTestCases(Function<File, TestCase> testCaseFactory, TestExecutor testExecutor) {
        if (GlobalConfig.isUseCacheMode()) {
            return loadSuccessfulTestCasesFromCache(testCaseFactory, testExecutor);
        } else {
            return executeAndFilterSuccessfulTestCases(testCaseFactory, testExecutor);
        }
    }
    
    /**
     * 从缓存文件加载成功的测试用例
     */
    private List<TestCase> loadSuccessfulTestCasesFromCache(Function<File, TestCase> testCaseFactory, TestExecutor testExecutor) {
        String cachePath = GlobalConfig.getValidTestCasesPath(rootPath);
        Path cacheFile = Paths.get(cachePath);
        
        if (!Files.exists(cacheFile)) {
            LoggerUtil.logExec(Level.WARNING, "缓存文件不存在: " + cachePath + ", 将执行完整过滤流程");
            return executeAndFilterSuccessfulTestCases(testCaseFactory, testExecutor);
        }
        
        try {
            List<String> validTestCases = Files.readAllLines(cacheFile);
            List<TestCase> successfulTestCases = new ArrayList<>();
            
            for (String line : validTestCases) {
                String testCasePath = line.trim();
                // 跳过空行和注释行
                if (testCasePath.isEmpty() || testCasePath.startsWith("#")) {
                    continue;
                }
                
                File testFile = new File(GlobalConfig.getJdkTestPath() + "/jdk/" + testCasePath);
                if (isValidTestFile(testFile)) {
                    TestCase testCase = testCaseFactory.apply(testFile);
                    // 设置为成功状态，跳过实际执行
                    testCase.setResult(new TestResult(TestResultKind.SUCCESS));
                    successfulTestCases.add(testCase);
                } else {
                    LoggerUtil.logExec(Level.WARNING, "缓存中的测试文件无效或不存在: " + testFile.getAbsolutePath());
                }
            }
            
            LoggerUtil.logExec(Level.INFO, "从缓存文件 " + cachePath + " 加载了 " + successfulTestCases.size() + " 个成功测试用例");
            return successfulTestCases;
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "读取缓存文件失败: " + cachePath + "\n" + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 执行测试并过滤成功的用例
     */
    private List<TestCase> executeAndFilterSuccessfulTestCases(Function<File, TestCase> testCaseFactory, TestExecutor testExecutor) {
        System.out.println("执行完整过滤流程：执行所有测试用例并过滤成功的用例...");
        
        List<File> files = getTestFiles();
        List<TestCase> allTestCases = files.stream()
                .filter(this::isValidTestFile)
                .map(testCaseFactory)
                .collect(Collectors.toList());
        
        try {
            // 执行所有测试用例
            executeTestCasesAsync(allTestCases, testExecutor).join();
            
            // 过滤出成功的测试用例
            List<TestCase> successfulTestCases = allTestCases.stream()
                    .filter(testCase -> testCase.getResult().isSuccess())
                    .collect(Collectors.toList());
            
            int totalCount = allTestCases.size();
            int successCount = successfulTestCases.size();
            int failureCount = totalCount - successCount;
            
            System.out.println(String.format("过滤完成 - 总计: %d, 成功: %d, 失败: %d (失败的已丢弃)", 
                    totalCount, successCount, failureCount));
            
            LoggerUtil.logExec(Level.INFO, String.format("成功测试用例过滤完成，保留 %d/%d 个测试用例进行后续处理", 
                    successCount, totalCount));
            
            // 可选：将成功的测试用例保存到缓存文件
            saveSuccessfulTestCasesToCache(successfulTestCases);
            
            return successfulTestCases;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "测试过滤过程中发生错误: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * 将成功的测试用例保存到缓存文件
     */
    private void saveSuccessfulTestCasesToCache(List<TestCase> successfulTestCases) {
        try {
            String cachePath = GlobalConfig.getValidTestCasesPath(rootPath);
            List<String> testCasePaths = successfulTestCases.stream()
                    .map(testCase -> {
                        String fullPath = testCase.getOriginFile().getAbsolutePath();
                        String jdkTestPath = GlobalConfig.getJdkTestPath() + "/jdk/";
                        if (fullPath.startsWith(jdkTestPath)) {
                            return fullPath.substring(jdkTestPath.length());
                        }
                        return fullPath;
                    })
                    .collect(Collectors.toList());
            
            Files.write(Paths.get(cachePath), testCasePaths);
            LoggerUtil.logExec(Level.INFO, "成功测试用例已保存到缓存文件: " + cachePath);
            
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存缓存文件失败: " + e.getMessage());
        }
    }

    public String getRootPath() {
        return rootPath;
    }

    public ArrayList<String> getTestCases() {
        return testCases;
    }
}
