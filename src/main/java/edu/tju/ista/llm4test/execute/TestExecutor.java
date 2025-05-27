package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * 测试执行器 - 用于执行JTreg测试并进行差异化测试
 * 
 * 主要特性：
 * - 支持多JDK差异化测试
 * - 超时控制和资源管理
 * - 自动重试机制
 * - 临时文件清理
 * 
 * 优化改进：
 * - 使用专用线程池替代协程提高性能
 * - 改进资源管理和异常处理
 * - 优化进程执行和超时控制
 * - 减少代码重复，提高可维护性
 * - 集成并发管理器，分离IO和CPU密集型任务
 */
public class TestExecutor {
    private final File resultDir;
    private final ConcurrentExecutionManager concurrentManager;
    
    // 配置常量
    private static final String[] EXEC_FLAGS = {"-Xint", "-Xcomp", "-Xmixed"};
    private static final Map<String, String> BASE_ENV = createBaseEnvironment();
    private static final String SYSTEM_PATH = System.getenv("PATH")
        .replace("/usr/lib/jvm/java-17-openjdk-amd64/bin:", "");
    private static final List<String> DEFAULT_JDKS = Arrays.asList(
        "/home/Java/HotSpot/jdk-17.0.14+7", 
        "/home/Java/HotSpot/jdk-21.0.6+7"
    );
    
    // 超时配置（毫秒）
    private static final long EXECUTION_TIMEOUT_MS = 600_000; // 10分钟
    private static final long JDK_TEST_TIMEOUT_MS = 60_000;   // 1分钟
    
    /**
     * 构造函数
     * @param resultDir 结果目录
     */
    public TestExecutor(File resultDir) {
        this.resultDir = resultDir;
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
    }
    
    /**
     * 创建基础环境变量
     */
    private static Map<String, String> createBaseEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("LANG", "en_US.UTF-8");
        return Collections.unmodifiableMap(env);
    }
    
    /**
     * 执行测试（主入口）
     * @param file 测试文件
     * @return 测试结果
     */
    public TestResult executeTest(File file) {
        return differentialTesting(file);
    }
    
    /**
     * 异步执行测试
     * @param file 测试文件
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> executeTestAsync(File file) {
        return differentialTestingAsync(file);
    }
    
    /**
     * 差异化测试 - 在多个JDK上运行测试并比较结果（同步版本）
     * @param file 测试文件
     * @return 测试结果
     */
    public TestResult differentialTesting(File file) {
        try {
            return differentialTestingAsync(file).get();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "差异化测试执行失败: " + e.getMessage());
            return new TestResult(TestResultKind.UNKNOWN);
        }
    }
    
    /**
     * 异步差异化测试 - 在多个JDK上并行运行测试并比较结果
     * @param file 测试文件
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> differentialTestingAsync(File file) {
        Map<String, TestOutput> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 并行执行多个JDK测试
        for (String jdk : DEFAULT_JDKS) {
            CompletableFuture<Void> future = concurrentManager.submitTestTask(() -> {
                try {
                    TestOutput output = runJtreg(file, jdk);
                    results.put(jdk, output);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, 
                        "JDK测试执行失败: " + jdk + " - " + e.getMessage());
                    results.put(jdk, new TestOutput("", e.getMessage(), -1));
                }
            });
            futures.add(future);
        }
        
        // 等待所有测试完成并合并结果
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                TestResult result = new TestResult();
                result.mergeResults(results);
                return result;
            })
            .exceptionally(ex -> {
                LoggerUtil.logExec(Level.WARNING, "差异化测试异常: " + ex.getMessage());
                TestResult result = new TestResult();
                result.mergeResults(results); // 即使有异常，也尝试合并已完成的结果
                return result;
            });
    }
    
    /**
     * 运行JTreg测试
     * @param file 测试文件
     * @param jdk JDK路径
     * @return 测试输出
     * @throws Exception 执行异常
     */
    private TestOutput runJtreg(File file, String jdk) throws Exception {
        // 清理临时文件
        clearJTworkFiles(file);
        
        // 构建命令和环境
        String reportDir = createReportDirectory();
        List<String> command = Arrays.asList(
            "jtreg", "-avm", "-ea", "-va", 
            "-r:" + reportDir, "-jdk:" + jdk, 
            file.getPath()
        );
        
        LoggerUtil.logExec(Level.INFO, "执行命令: " + String.join(" ", command));
        
        // 设置环境变量
        Map<String, String> env = new HashMap<>(BASE_ENV);
        env.put("PATH", jdk + "/bin:" + SYSTEM_PATH);
        env.put("JAVA_HOME", jdk);
        
        // 执行进程
        return executeProcess(command, env, file);
    }
    
    /**
     * 执行进程并处理输出
     * @param command 命令列表
     * @param env 环境变量
     * @param file 测试文件（用于日志）
     * @return 测试输出
     * @throws Exception 执行异常
     */
    private TestOutput executeProcess(List<String> command, Map<String, String> env, File file) 
            throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(env);
        
        Process process = processBuilder.start();
        
        // 并发读取输出流 - 使用并发管理器的批量处理线程池
        CompletableFuture<String> stdoutFuture = 
            concurrentManager.submitBatchTask(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = 
            concurrentManager.submitBatchTask(() -> readStream(process.getErrorStream()));
        
        // 等待进程完成或超时
        boolean finished = process.waitFor(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            LoggerUtil.logExec(Level.INFO, 
                "执行超时: " + file.getPath() + "\n" + String.join(" ", command) + "\ntimeout");
            
            String stdout = getCompletedResult(stdoutFuture, "");
            String stderr = getCompletedResult(stderrFuture, "");
            return new TestOutput(stdout, stderr, 124);
        }
        
        int exitValue = process.exitValue();
        String stdout = stdoutFuture.get();
        String stderr = stderrFuture.get();
        TestOutput output = new TestOutput(stdout, stderr, exitValue);
        
        // 记录日志
        if (exitValue != 0) {
            LoggerUtil.logExec(Level.SEVERE,
                "调试信息: -------------------------\n" +
                file.getPath() + "\n" +
                String.join(" ", command) + "\n" +
                output + "\n" +
                "-------------------------------");
        } else {
            LoggerUtil.logExec(Level.INFO, 
                "运行成功: " + file.getPath() + "\n" + String.join(" ", command) + "\n" + output);
        }
        
        return output;
    }
    
    /**
     * 安全获取CompletableFuture结果
     */
    private String getCompletedResult(CompletableFuture<String> future, String defaultValue) {
        try {
            return future.isDone() ? future.get() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
    
    /**
     * 读取输入流内容
     * @param stream 输入流
     * @return 流内容
     */
    private String readStream(InputStream stream) {
        try {
            return new String(stream.readAllBytes());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "读取流失败: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * 清理JTreg工作文件
     * @param file 测试文件
     */
    public void clearJTworkFiles(File file) {
        try {
            String baseName = file.getName().replace(".java", "");
            File jtWork = new File("JTwork");
            
            Path relativePath = resultDir.toPath().toAbsolutePath()
                .relativize(file.toPath().toAbsolutePath()).getParent();
            
            if (relativePath == null) {
                return;
            }
            
            // 要清理的文件
            String[] filesToClean = {
                baseName + ".class",
                baseName + ".jtr", 
                baseName + ".d"
            };
            
            String[] directories = {"classes", "", ""};
            
            for (int i = 0; i < filesToClean.length; i++) {
                Path targetPath = jtWork.toPath();
                if (!directories[i].isEmpty()) {
                    targetPath = targetPath.resolve(directories[i]);
                }
                targetPath = targetPath.resolve(relativePath).resolve(filesToClean[i]);
                
                File targetFile = targetPath.toFile();
                if (targetFile.exists()) {
                    boolean deleted = targetFile.delete();
                    if (!deleted) {
                        LoggerUtil.logExec(Level.FINE, "无法删除文件: " + targetFile.getPath());
                    }
                }
            }
        } catch (Exception e) {
            // 清理失败时静默处理，避免影响主要流程
            LoggerUtil.logExec(Level.FINE, "清理JTwork文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建报告目录
     * @return 报告目录路径
     */
    private String createReportDirectory() {
        long threadId = Thread.currentThread().getId();
        String dirName = "tmp/tmp_" + threadId + "_" + System.nanoTime();
        File reportDir = new File(dirName);
        
        if (!reportDir.exists()) {
            boolean created = reportDir.mkdirs();
            if (!created) {
                LoggerUtil.logExec(Level.WARNING, "无法创建报告目录: " + dirName);
            }
        }
        
        return reportDir.getPath();
    }
    
    /**
     * 测试JDK环境
     */
    public void testJDKEnvironment() {
        LoggerUtil.logExec(Level.INFO, "开始测试JDK环境...");
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (String jdk : DEFAULT_JDKS) {
            CompletableFuture<Void> future = concurrentManager.submitTestTask(() -> {
                try {
                    testSingleJDK(jdk);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.SEVERE, "JDK测试失败: " + jdk + " - " + e.getMessage());
                }
            });
            futures.add(future);
        }
        
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(JDK_TEST_TIMEOUT_MS * DEFAULT_JDKS.size(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "JDK环境测试异常: " + e.getMessage());
        }
        
        LoggerUtil.logExec(Level.INFO, "JDK环境测试完成");
    }
    
    /**
     * 测试单个JDK
     */
    private void testSingleJDK(String jdk) throws Exception {
        Map<String, String> jdkEnv = new HashMap<>(BASE_ENV);
        jdkEnv.put("PATH", jdk + "/bin:" + SYSTEM_PATH);
        jdkEnv.put("JAVA_HOME", jdk);
        
        List<String> testCommand = Arrays.asList("java", "-version");
        
        ProcessBuilder processBuilder = new ProcessBuilder(testCommand);
        processBuilder.environment().putAll(jdkEnv);
        Process process = processBuilder.start();
        
        boolean finished = process.waitFor(JDK_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        if (!finished) {
            process.destroyForcibly();
            LoggerUtil.logExec(Level.SEVERE, "JDK测试超时: " + jdk);
            return;
        }
        
        int exitValue = process.exitValue();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        
        if (exitValue == 0) {
            LoggerUtil.logExec(Level.INFO, "JDK测试成功: " + jdk);
            LoggerUtil.logExec(Level.FINE, "输出: " + stdout + stderr);
        } else {
            LoggerUtil.logExec(Level.SEVERE, 
                "JDK测试失败: " + jdk + " exitCode=" + exitValue + "\n" + stderr);
        }
    }
    
    /**
     * 清理临时目录
     */
    public void clearTempDirectories() {
        String[] dirsToClean = {"tmp", "JTwork"};
        
        for (String dirName : dirsToClean) {
            File dir = new File(dirName);
            if (dir.exists()) {
                boolean deleted = deleteRecursively(dir);
                if (deleted) {
                    LoggerUtil.logExec(Level.INFO, "已清理目录: " + dirName);
                } else {
                    LoggerUtil.logExec(Level.WARNING, "无法清理目录: " + dirName);
                }
            }
        }
    }
    
    /**
     * 递归删除目录
     * @param file 要删除的文件或目录
     * @return 是否删除成功
     */
    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
    
    /**
     * 关闭执行器并清理资源
     * 注意：这里不关闭并发管理器，因为它是单例且可能被其他组件使用
     */
    public void shutdown() {
        LoggerUtil.logExec(Level.INFO, "TestExecutor关闭完成");
    }
    
    /**
     * 获取默认JDK列表（用于测试）
     * @return JDK路径列表
     */
    public List<String> getDefaultJDKs() {
        return new ArrayList<>(DEFAULT_JDKS);
    }
    
    /**
     * 获取并发执行状态
     */
    public void logConcurrentStatus() {
        concurrentManager.logStatus();
    }
} 