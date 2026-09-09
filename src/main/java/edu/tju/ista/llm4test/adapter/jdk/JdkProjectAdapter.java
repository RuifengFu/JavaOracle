package edu.tju.ista.llm4test.adapter.jdk;

import edu.tju.ista.llm4test.adapter.ProjectAdapter;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestOutput;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.adapter.HarnessOutputParser;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * JDK/jtreg 目标适配器。
 * <p>
 * 逻辑自 {@code TestSuite}（测试发现）与 {@code TestExecutor}（jtreg多JDK差分执行、环境检查）
 * 原样迁移，行为保持不变：
 * <ul>
 *   <li>发现：{@code jtreg -l <路径>}，失败时回退目录扫描（相对 suiteBasePath）</li>
 *   <li>执行：对 jdkPaths 中每个JDK执行 {@code jtreg -ea -va -jdk:<jdk>}，合并为差分结果</li>
 *   <li>oracle：多JDK结果不一致 → DIFF；执行失败细节交由上层LLM裁决</li>
 * </ul>
 */
public class JdkProjectAdapter implements ProjectAdapter {

    /** 解析器无状态，进程内共享一份即可 */
    private static final HarnessOutputParser JTREG_OUTPUT_PARSER = new JtregOutputParser();

    private final List<String> jdkPaths;

    private static final Map<String, String> BASE_ENV = createBaseEnvironment();
    private static final String SYSTEM_PATH = System.getenv("PATH")
        .replace("/usr/lib/jvm/java-17-openjdk-amd64/bin:", "");

    // 超时配置（毫秒）
    private static final long EXECUTION_TIMEOUT_MS = 600_000; // 10分钟
    private static final long JDK_TEST_TIMEOUT_MS = 60_000;   // 1分钟

    public JdkProjectAdapter() {
        this.jdkPaths = GlobalConfig.getJdkPaths();
    }

    @Override
    public String id() {
        return "jdk";
    }

    /**
     * jtreg harness 指令片段（与历史模板硬编码文案一致）
     */
    @Override
    public Map<String, String> harnessDirectives() {
        Map<String, String> directives = new HashMap<>();
        directives.put("name", "jtreg");
        directives.put("tagList", "(`@test`, `@bug`, `@summary`, `@run`, `@build`, `@library`, ...)");
        directives.put("tagExample", """
                ```
                /*
                 * @test
                 * @bug 4160406 4705734 4707389 6358355 7032154
                 * @summary Tests for Float.parseFloat method
                 */
                ```""");
        directives.put("executeToolName", JtregExecuteTool.TOOL_NAME);
        return directives;
    }

    // ==================== 测试发现（自 TestSuite 迁移） ====================

    @Override
    public List<String> discoverTests(String rootPath) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("jtreg", "-l", rootPath);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            String[] lines = output.split("\n");
            if (lines.length >= 3) {
                var list = Arrays.asList(lines).subList(1, lines.length - 1).stream()
                        .filter(s -> s.endsWith(".java"))
                        .collect(Collectors.toCollection(ArrayList::new));
                if (!list.isEmpty()) {
                    return list;
                }
            }
            if (exitCode != 0) {
                LoggerUtil.logExec(Level.WARNING, "jtreg -l 退出码: " + exitCode + ", 输出: " + output.trim());
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "jtreg -l 执行失败: " + e.getMessage());
        }
        // Fallback: scan directory for .java files when jtreg returns no results
        return scanDirectoryForJavaFiles(rootPath);
    }

    /**
     * 当jtreg -l无法发现测试用例时，直接扫描目录中的.java文件作为后备方案（自 TestSuite 迁移）
     */
    private ArrayList<String> scanDirectoryForJavaFiles(String rootPath) {
        try {
            Path rootDir = Paths.get(rootPath);
            if (!Files.isDirectory(rootDir)) {
                // rootPath might be a single file
                if (Files.isRegularFile(rootDir) && rootPath.endsWith(".java")) {
                    Path basePath = Paths.get(GlobalConfig.getSuiteBasePath()).toAbsolutePath().normalize();
                    String relativePath = basePath.relativize(rootDir.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    ArrayList<String> result = new ArrayList<>();
                    result.add(relativePath);
                    LoggerUtil.logExec(Level.INFO, "通过文件路径直接加载 1 个测试用例");
                    return result;
                }
                LoggerUtil.logExec(Level.WARNING, "测试路径不存在: " + rootPath);
                return new ArrayList<>();
            }
            Path basePath = Paths.get(GlobalConfig.getSuiteBasePath()).toAbsolutePath().normalize();
            ArrayList<String> javaFiles = Files.walk(rootDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> basePath.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!javaFiles.isEmpty()) {
                LoggerUtil.logExec(Level.INFO, "通过目录扫描发现 " + javaFiles.size() + " 个Java文件: " + rootPath);
            }
            return javaFiles;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "目录扫描失败: " + rootPath + " - " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public File resolveTestFile(String relativeTestPath) {
        // 自 TestSuite.getTestFiles 迁移：jdkTestPath + "/jdk/" + 相对路径
        // （等价于 resolveSuitePath：suiteBasePath 默认与配置值均为 jdk17u-dev/test/jdk/）
        return new File(resolveSuitePath(relativeTestPath));
    }

    @Override
    public TestExecuteTool createExecuteTool() {
        return new JtregExecuteTool();
    }

    @Override
    public HarnessOutputParser outputParser() {
        return JTREG_OUTPUT_PARSER;
    }

    /** jtreg 码表（与迁移前 TestResult 内联的特判逐字一致） */
    @Override
    public TestResultKind classifyExitValue(int exitValue) {
        return switch (exitValue) {
            case 0 -> TestResultKind.SUCCESS;
            case 3 -> TestResultKind.EXECUTE_ERROR;
            case 5 -> TestResultKind.WRONG_FORMAT;
            case 124 -> TestResultKind.EXECUTE_TIMEOUT;
            default -> TestResultKind.TEST_FAIL;
        };
    }

    /** 与迁移前 TestOutput.getSimpleOutput 的标签逐字一致 */
    @Override
    public String describeExitValue(int exitValue) {
        return switch (exitValue) {
            case 0 -> "SUCCESS";
            case 2 -> "TEST_FAIL";
            case 3 -> "ENV_ERROR";
            case 124 -> "TIMEOUT";
            default -> "UNKNOWN";
        };
    }

    /** 套件根：jdk17u-dev/test/jdk/（发现结果与通过列表都相对于它） */
    @Override
    public String suiteRoot() {
        return GlobalConfig.getSuiteBasePath();
    }

    /**
     * 复制根比套件根高一层：整个 jdk17u-dev/test 被复制进 testDir，
     * 因此工作区里的路径形如 test/jdk/java/lang/Byte/Decode.java。
     */
    @Override
    public String workspaceSourceRoot() {
        return GlobalConfig.getJdkTestPath();
    }

    // ==================== 测试执行（自 TestExecutor 迁移） ====================

    /**
     * 差异化测试 - 在多个JDK上运行测试并比较结果（自 TestExecutor.differentialTesting 迁移）
     */
    @Override
    public TestResult executeTest(TestCase testCase) {
        Map<String, TestOutput> results = new HashMap<>();
        File file = testCase.getFile();

        LoggerUtil.logExec(Level.INFO, "开始差异化测试: " + file.getName());

        // 顺序执行每个JDK的测试，避免JTreg工作文件夹冲突
        for (String jdk : jdkPaths) {
            try {
                LoggerUtil.logExec(Level.INFO, "开始执行JDK测试: " + jdk + " - " + file.getName());
                TestOutput output = runJtregWithTestCase(testCase, jdk);
                results.put(jdk, output);
                LoggerUtil.logExec(Level.INFO, "完成JDK测试: " + jdk + " - " + file.getName());
            } catch (Exception e) {
                e.printStackTrace();
                LoggerUtil.logExec(Level.WARNING,
                    "JDK测试执行失败: " + jdk + " - " + e.getMessage());
                results.put(jdk, new TestOutput("", e.getMessage(), -1));
            }
        }

        // 合并结果
        TestResult result = new TestResult();
        result.mergeResults(results);
        LoggerUtil.logExec(Level.INFO,
            String.format("差异化测试完成: %s - 共执行 %d 个JDK版本",
                file.getName(), results.size()));

        // 清理所有临时文件
        testCase.clearAllTempFiles();

        return result;
    }

    /**
     * 运行JTreg测试（使用TestCase管理临时文件）（自 TestExecutor 迁移）
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
            "jtreg", "-ea", "-va",
            "-r:" + jtReportDir,    // 报告目录
            "-w:" + jtWorkDir,      // 工作目录
            "-jdk:" + jdk,
            file.getPath()
        );

        LoggerUtil.logExec(Level.INFO,
            String.format("执行JDK测试 [%s]: %s - %s", jdkName, file.getName(), String.join(" ", command)));

        // 设置环境变量
        Map<String, String> env = new HashMap<>(BASE_ENV);
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
     * 执行进程并处理输出（自 TestExecutor 迁移）
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

        // 并发消费流 + 超时 + 终止进程树，见 ProcessRunner
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(command, env, EXECUTION_TIMEOUT_MS);
        } catch (IOException e) {
            String errorMsg = String.format("启动进程失败: %s - %s", String.join(" ", command), e.getMessage());
            LoggerUtil.logExec(Level.SEVERE, errorMsg);
            throw new Exception(errorMsg, e);
        } catch (InterruptedException e) {
            String errorMsg = String.format("进程等待被中断: %s", String.join(" ", command));
            LoggerUtil.logExec(Level.WARNING, errorMsg);
            throw new Exception(errorMsg, e);
        }

        if (result.timedOut()) {
            String timeoutMsg = String.format("执行超时 (%d ms): %s", EXECUTION_TIMEOUT_MS, String.join(" ", command));
            LoggerUtil.logExec(Level.WARNING, timeoutMsg);
            // 退出码沿用 jtreg 语义的 124（ProcessRunner 返回的是中性占位）
            return new TestOutput(result.stdout(),
                    result.stderr() + "\n[TIMEOUT after " + EXECUTION_TIMEOUT_MS + " ms]", 124);
        }

        int exitValue = result.exitValue();
        String stdout = result.stdout();
        String stderr = result.stderr();

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

    // ==================== 环境（自 TestExecutor 迁移） ====================

    @Override
    public void checkEnvironment() {
        LoggerUtil.logExec(Level.INFO, "检查执行环境...");

        // 检查jtreg命令是否可用
        try {
            ProcessRunner.Result result = ProcessRunner.run(
                    List.of("jtreg", "-version"), TimeUnit.SECONDS.toMillis(5));

            if (result.timedOut()) {
                LoggerUtil.logExec(Level.WARNING, "jtreg命令检查超时");
            } else if (result.exitValue() == 0) {
                LoggerUtil.logExec(Level.INFO, "jtreg命令检查通过");
            } else {
                LoggerUtil.logExec(Level.WARNING, "jtreg命令返回非零退出码: " + result.exitValue());
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "jtreg不可用: " + e.getMessage());
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

    @Override
    public void verifyEnvironment() {
        LoggerUtil.logExec(Level.INFO, "开始测试JDK环境...");

        // 直接顺序执行每个JDK测试，避免异步调用和阻塞等待
        for (String jdk : jdkPaths) {
            try {
                testSingleJDK(jdk);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.SEVERE, "JDK测试失败: " + jdk + " - " + e.getMessage());
            }
        }

        LoggerUtil.logExec(Level.INFO, "JDK环境测试完成");
    }

    /**
     * 测试单个JDK（自 TestExecutor.testSingleJDK 迁移）
     */
    private void testSingleJDK(String jdk) throws Exception {
        Map<String, String> jdkEnv = new HashMap<>(BASE_ENV);
        jdkEnv.put("PATH", jdk + "/bin:" + SYSTEM_PATH);
        jdkEnv.put("JAVA_HOME", jdk);

        List<String> testCommand = Arrays.asList("java", "-version");

        ProcessRunner.Result result = ProcessRunner.run(testCommand, jdkEnv, JDK_TEST_TIMEOUT_MS);

        if (result.timedOut()) {
            LoggerUtil.logExec(Level.SEVERE, "JDK测试超时: " + jdk);
            return;
        }

        int exitValue = result.exitValue();
        String stdout = result.stdout();
        String stderr = result.stderr();

        if (exitValue == 0) {
            LoggerUtil.logExec(Level.INFO, "JDK测试成功: " + jdk);
            LoggerUtil.logExec(Level.FINE, "输出: " + stdout + stderr);
        } else {
            LoggerUtil.logExec(Level.SEVERE,
                "JDK测试失败: " + jdk + " exitCode=" + exitValue + "\n" + stderr);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 清理临时目录（自 TestExecutor.clearTempDirectories 迁移；谨慎使用）
     */
    @Override
    public void cleanupWorkspace() {
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
     * 获取JDK路径列表（原 getDefaultJDKs）
     */
    public List<String> getDefaultJDKs() {
        return new ArrayList<>(jdkPaths);
    }

    private static Map<String, String> createBaseEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("LANG", "en_US.UTF-8");
        return Collections.unmodifiableMap(env);
    }
}