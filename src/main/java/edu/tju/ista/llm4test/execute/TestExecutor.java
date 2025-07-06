package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;


public class TestExecutor {
    private final ConcurrentExecutionManager concurrentManager;
    private final List<String> jdkPaths;
    
    // 配置常量
    private static final String[] EXEC_FLAGS = {"-Xint", "-Xcomp", "-Xmixed"};
    private static final Map<String, String> BASE_ENV = createBaseEnvironment();
    private static final String SYSTEM_PATH = System.getenv("PATH")
        .replace("/usr/lib/jvm/java-17-openjdk-amd64/bin:", "");
    
    // 超时配置（毫秒）
    private static final long EXECUTION_TIMEOUT_MS = 600_000; // 10分钟
    private static final long JDK_TEST_TIMEOUT_MS = 60_000;   // 1分钟
    
    /**
     * 构造函数
     */
    public TestExecutor() {
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
        this.jdkPaths = GlobalConfig.getJdkPaths();
        // 环境检查
        checkEnvironment();
    }
    
    /**
     * 检查执行环境
     */
    private void checkEnvironment() {
        LoggerUtil.logExec(Level.INFO, "检查执行环境...");
        
        // 检查jtreg命令是否可用
        try {
            ProcessBuilder pb = new ProcessBuilder("jtreg", "-version");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            
            if (finished) {
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    LoggerUtil.logExec(Level.INFO, "jtreg命令检查通过");
                } else {
                    LoggerUtil.logExec(Level.WARNING, "jtreg命令返回非零退出码: " + exitCode);
                }
            } else {
                process.destroyForcibly();
                LoggerUtil.logExec(Level.WARNING, "jtreg命令检查超时");
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "jtreg命令不可用: " + e.getMessage());
            LoggerUtil.logExec(Level.INFO, "请确保jtreg已安装并在PATH中");
        }
        
        // 检查JDK路径
        for (String jdk : jdkPaths) {
            File jdkDir = new File(jdk);
            if (jdkDir.exists() && jdkDir.isDirectory()) {
                LoggerUtil.logExec(Level.INFO, "JDK路径检查通过: " + jdk);
            } else {
                LoggerUtil.logExec(Level.WARNING, "JDK路径不存在: " + jdk);
            }
        }
        
        LoggerUtil.logExec(Level.INFO, "环境检查完成");
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
     * 执行测试（使用TestCase）
     * @param testCase 测试用例
     * @return 测试结果
     */
    public TestResult executeTest(TestCase testCase) {
        return differentialTesting(testCase);
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
     * 异步执行测试（使用TestCase）
     * @param testCase 测试用例
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> executeTestAsync(TestCase testCase) {
        return differentialTestingAsync(testCase);
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
     * 差异化测试 - 在多个JDK上运行测试并比较结果（使用TestCase）
     * @param testCase 测试用例
     * @return 测试结果
     */
    public TestResult differentialTesting(TestCase testCase) {
        try {
            return differentialTestingAsync(testCase).get();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "差异化测试执行失败: " + e.getMessage());
            return new TestResult(TestResultKind.UNKNOWN);
        }
    }
    
    /**
     * 异步差异化测试 - 在多个JDK上顺序运行测试并比较结果
     * 注意：由于JTreg框架会共用工作文件夹，必须顺序执行避免冲突
     * @param file 测试文件
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> differentialTestingAsync(File file) {
        TestCase testCase = new TestCase(file);
        return differentialTestingAsync(testCase);
    }
    
    /**
     * 异步差异化测试 - 在多个JDK上顺序运行测试并比较结果（使用TestCase）
     * 注意：由于JTreg框架会共用工作文件夹，必须顺序执行避免冲突
     * @param testCase 测试用例
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> differentialTestingAsync(TestCase testCase) {
        Map<String, TestOutput> results = new ConcurrentHashMap<>();
        File file = testCase.getFile();
        
        // 创建顺序执行链，避免JTreg工作文件夹冲突
        CompletableFuture<Void> sequentialChain = CompletableFuture.completedFuture(null);
        
        // 为每个JDK创建顺序执行链
        for (String jdk : jdkPaths) {
            sequentialChain = sequentialChain.thenCompose(v -> 
                concurrentManager.submitTestTask(() -> {
                    try {
                        LoggerUtil.logExec(Level.INFO, "开始执行JDK测试: " + jdk + " - " + file.getName());
                        TestOutput output = runJtregWithTestCase(testCase, jdk);
                        results.put(jdk, output);
                        LoggerUtil.logExec(Level.INFO, "完成JDK测试: " + jdk + " - " + file.getName());
                        return null;
                    } catch (Exception e) {
                        e.printStackTrace();
                        LoggerUtil.logExec(Level.WARNING, 
                            "JDK测试执行失败: " + jdk + " - " + e.getMessage());
                        results.put(jdk, new TestOutput("", e.getMessage(), -1));
                        return null;
                    }
                })
            );
        }
        
        // 等待所有顺序测试完成并合并结果
        return sequentialChain
            .thenApply(v -> {
                TestResult result = new TestResult();
                result.mergeResults(results);
                LoggerUtil.logExec(Level.INFO, 
                    String.format("差异化测试完成: %s - 共执行 %d 个JDK版本", 
                        file.getName(), results.size()));
                
                // 清理所有临时文件
                testCase.clearAllTempFiles();
                
                return result;
            })
            .exceptionally(ex -> {
                LoggerUtil.logExec(Level.WARNING, "差异化测试异常: " + ex.getMessage());
                
                // 异常情况下也要清理临时文件
                testCase.clearAllTempFiles();
                
                TestResult result = new TestResult();
                result.mergeResults(results); // 即使有异常，也尝试合并已完成的结果
                return result;
            });
    }
    
    /**
     * 运行JTreg测试（使用TestCase管理临时文件）
     * @param testCase 测试用例
     * @param jdk JDK路径
     * @return 测试输出
     * @throws Exception 执行异常
     */
    private TestOutput runJtregWithTestCase(TestCase testCase, String jdk) throws Exception {
        File file = testCase.getFile();
        String jdkName = Paths.get(jdk).getFileName().toString();
        
        // 清理当前测试文件相关的特定文件，避免影响其他并发测试
        testCase.clearSpecificJTworkFiles();
        
        // 获取JDK特定的临时目录
        String tempDir = testCase.getTempDirectory(jdkName);
        
        // 在临时目录下创建 JTwork 和 JTreport 子目录
        String jtWorkDir = tempDir + "/JTwork";
        String jtReportDir = tempDir + "/JTreport";
        
        // 确保子目录存在
        new File(jtWorkDir).mkdirs();
        new File(jtReportDir).mkdirs();
        
        // 构建命令，使用临时目录下的子目录
        List<String> command = Arrays.asList(
            "jtreg", "-avm", "-ea", "-va", 
            "-r:" + jtReportDir,    // 报告目录
            "-w:" + jtWorkDir,      // 工作目录
            "-jdk:" + jdk, 
            file.getPath()
        );
        
        LoggerUtil.logExec(Level.INFO, 
            String.format("执行JDK测试 [%s]: %s - %s", jdkName, file.getName(), String.join(" ", command)));
        
        // 设置环境变量
        Map<String, String> env = new HashMap<>(BASE_ENV);
        // 为隔离性，暂时注释掉PATH和JAVA_HOME的设置
         env.put("PATH", jdk + "/bin:" + SYSTEM_PATH);
         env.put("JAVA_HOME", jdk);
        
        try {
            // 执行进程
            TestOutput result = executeProcess(command, env, file);
            
            // 执行完成后清理当前JDK的临时文件
            testCase.clearTempFiles(jdkName);
            
            return result;
        } catch (Exception e) {
            // 异常情况下也要清理
            testCase.clearTempFiles(jdkName);
            throw e;
        }
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
        
        // 详细记录执行信息，便于调试
        LoggerUtil.logExec(Level.INFO, 
            String.format("准备执行进程: %s", String.join(" ", command)));
        LoggerUtil.logExec(Level.FINE, 
            String.format("工作目录: %s", System.getProperty("user.dir")));
        LoggerUtil.logExec(Level.FINE, 
            String.format("PATH: %s", env.get("PATH")));
        LoggerUtil.logExec(Level.FINE, 
            String.format("JAVA_HOME: %s", env.get("JAVA_HOME")));
        
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(env);
        
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            String errorMsg = String.format("启动进程失败: %s - %s", String.join(" ", command), e.getMessage());
            LoggerUtil.logExec(Level.SEVERE, errorMsg);
            throw new Exception(errorMsg, e);
        }
        
        // 并发读取输出流 - 使用并发管理器的批量处理线程池
        CompletableFuture<String> stdoutFuture = 
            concurrentManager.submitBatchTask(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = 
            concurrentManager.submitBatchTask(() -> readStream(process.getErrorStream()));
        
        // 等待进程完成或超时
        boolean finished;
        try {
            finished = process.waitFor(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroy();
            process.destroyForcibly();
            String errorMsg = String.format("进程等待被中断: %s", String.join(" ", command));
            LoggerUtil.logExec(Level.WARNING, errorMsg);
            Thread.currentThread().interrupt();
            throw new Exception(errorMsg, e);
        }
        
        if (!finished) {
            process.destroy();
            process.destroyForcibly();
            String timeoutMsg = String.format("执行超时 (%d ms): %s", EXECUTION_TIMEOUT_MS, String.join(" ", command));
            LoggerUtil.logExec(Level.WARNING, timeoutMsg);
            
            String stdout = getCompletedResult(stdoutFuture, "");
            String stderr = getCompletedResult(stderrFuture, "");
            return new TestOutput(stdout, stderr + "\n[TIMEOUT after " + EXECUTION_TIMEOUT_MS + " ms]", 124);
        }
        
        int exitValue = process.exitValue();
        String stdout, stderr;
        
        try {
            stdout = stdoutFuture.get();
            stderr = stderrFuture.get();
        } catch (Exception e) {
            String errorMsg = String.format("读取进程输出失败: %s - %s", String.join(" ", command), e.getMessage());
            LoggerUtil.logExec(Level.WARNING, errorMsg);
            stdout = "";
            stderr = "读取输出失败: " + e.getMessage();
        }
        
        TestOutput output = new TestOutput(stdout, stderr, exitValue);
        
        // 记录详细的执行结果
        if (exitValue != 0) {
            // 安全地处理可能为null的值
            String filePath = file != null ? file.getPath() : "unknown";
            String commandStr = String.join(" ", command);
            String stdoutStr = stdout != null ? 
                (stdout.length() > 500 ? stdout.substring(0, 500) + "..." : stdout) : "";
            String stderrStr = stderr != null ? 
                (stderr.length() > 500 ? stderr.substring(0, 500) + "..." : stderr) : "";
            
            String errorMessage = String.format(
                "进程执行失败 [退出码: %d]\n" +
                "文件: %s\n" +
                "命令: %s\n" +
                "标准输出: %s\n" +
                "错误输出: %s\n" +
                "-------------------------------",
                exitValue, filePath, commandStr, stdoutStr, stderrStr);
            if (exitValue <= 3 && exitValue != 1) {
                LoggerUtil.logExec(Level.FINE, errorMessage);
            } else {
                LoggerUtil.logExec(Level.SEVERE, errorMessage);
            }
        } else {
            String filePath = file != null ? file.getPath() : "unknown";
            LoggerUtil.logExec(Level.INFO, 
                String.format("进程执行成功: %s", filePath));
            LoggerUtil.logExec(Level.FINE, 
                String.format("输出: %s", output.toString()));
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
     * 清理临时目录（谨慎使用！）
     * 警告：此方法会删除所有临时文件，可能影响正在运行的其他测试
     * 建议只在以下情况使用：
     * 1. 应用程序启动时
     * 2. 应用程序关闭时
     * 3. 确认没有其他测试在运行时
     */
    public void clearTempDirectories() {
        LoggerUtil.logExec(Level.WARNING, "执行全局临时目录清理，这可能影响其他正在运行的测试");
        
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
     * 测试JDK环境
     */
    public void testJDKEnvironment() {
        LoggerUtil.logExec(Level.INFO, "开始测试JDK环境...");
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (String jdk : jdkPaths) {
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
                .get(JDK_TEST_TIMEOUT_MS * jdkPaths.size(), TimeUnit.MILLISECONDS);
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
        return new ArrayList<>(jdkPaths);
    }
    
    /**
     * 获取并发执行状态
     */
    public void logConcurrentStatus() {
        concurrentManager.logStatus();
    }
} 