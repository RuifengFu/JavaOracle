package edu.tju.ista.llm4test.adapter.maven;

import edu.tju.ista.llm4test.adapter.HarnessOutputParser;
import edu.tju.ista.llm4test.adapter.ProjectAdapter;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestOutput;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 第三方 Maven 仓库适配器：增强被测仓库自身的 JUnit 测试。
 * <p>
 * 工作方式：
 * <ul>
 *   <li>发现：扫描 {@code src/test/java} 下含 JUnit 测试注解的测试类</li>
 *   <li>执行：javac 编译增强用例（对齐仓库 classpath）→
 *       junit-platform-console-standalone 运行 → 按退出码分类</li>
 *   <li>oracle：单环境执行 + 上层 LLM 裁决（无差分）</li>
 *   <li>workspace：一次性 {@code mvn test-compile dependency:build-classpath}，
 *       classpath 缓存于 {@code .llm4test/classpath.txt}（pom 变更自动失效）</li>
 * </ul>
 */
public class MavenProjectAdapter implements ProjectAdapter {

    /** 解析器无状态，进程内共享一份即可 */
    private static final HarnessOutputParser JUNIT5_OUTPUT_PARSER = new JUnit5OutputParser();

    private static final long EXECUTION_TIMEOUT_MS = 600_000;   // 单次测试执行上限
    private static final long BUILD_TIMEOUT_MS = 600_000;       // mvn 构建上限
    private static final String WORKSPACE_DIR = ".llm4test";

    private final String projectRoot;
    private final Path testSourceRoot;
    private final String junitConsoleJar;
    private volatile String cachedClasspath;

    /** JUnit5/JUnit4 测试注解标记（发现用，足够识别测试类） */
    private static final List<String> TEST_MARKERS = List.of(
            "@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory", "@TestTemplate");

    public MavenProjectAdapter() {
        this(GlobalConfig.getProjectRoot(), GlobalConfig.getJUnitConsoleJar());
    }

    public MavenProjectAdapter(String projectRoot, String junitConsoleJar) {
        this.projectRoot = projectRoot;
        this.junitConsoleJar = junitConsoleJar;
        // 绝对化+规范化：project.root 默认是相对路径 "."，而发现阶段 relativize 的
        // 是绝对路径；Path.relativize 不允许混用相对/绝对形式，否则整个发现静默返回空。
        this.testSourceRoot = Paths.get(projectRoot, "src", "test", "java")
                .toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "maven";
    }

    // ==================== 测试发现 ====================

    @Override
    public List<String> discoverTests(String rootPath) {
        Path base = resolveScanBase(rootPath);
        if (base == null || !Files.isDirectory(base)) {
            LoggerUtil.logExec(Level.WARNING, "Maven测试路径不存在: " + rootPath + " (期望位于 " + testSourceRoot + " 下)");
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.walk(base)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(this::containsTestMarker)
                    .map(p -> testSourceRoot.relativize(p.toAbsolutePath().normalize())
                            .toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Maven测试发现失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public File resolveTestFile(String relativeTestPath) {
        return testSourceRoot.resolve(relativeTestPath).toFile();
    }

    @Override
    public TestExecuteTool createExecuteTool() {
        return new MavenExecuteTool(this);
    }

    @Override
    public HarnessOutputParser outputParser() {
        return JUNIT5_OUTPUT_PARSER;
    }

    /**
     * junit-platform-console 的退出码：0 全通过、1 有测试失败、
     * 2 是 {@code --fail-if-no-tests} 没匹配到用例。
     * <p>
     * 分类上 2 仍归 TEST_FAIL（保持既有行为），但标签区分开——它通常意味着
     * FQN 推导错了（文件名 ≠ 主类名），而不是被测代码有问题。
     */
    @Override
    public TestResultKind classifyExitValue(int exitValue) {
        return exitValue == 0 ? TestResultKind.SUCCESS : TestResultKind.TEST_FAIL;
    }

    @Override
    public String describeExitValue(int exitValue) {
        return switch (exitValue) {
            case 0 -> "SUCCESS";
            case 1 -> "TEST_FAIL";
            case 2 -> "NO_TESTS_FOUND";
            case -1 -> "TIMEOUT";
            default -> "UNKNOWN";
        };
    }

    /** 套件根即测试源根：src/test/java（发现结果与通过列表都相对于它） */
    @Override
    public String suiteRoot() {
        return testSourceRoot.toString();
    }

    private Path resolveScanBase(String rootPath) {
        if (rootPath == null || rootPath.isBlank() || ".".equals(rootPath.trim())) {
            return testSourceRoot;
        }
        Path p = Paths.get(rootPath);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        // 相对路径：优先解释为相对测试根（与JDK模式的包路径语义对齐）
        return testSourceRoot.resolve(rootPath).normalize();
    }

    private boolean containsTestMarker(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return TEST_MARKERS.stream().anyMatch(content::contains);
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 测试执行 ====================

    @Override
    public TestResult executeTest(TestCase testCase) {
        File file = testCase.getFile();
        LoggerUtil.logExec(Level.INFO, "开始Maven测试执行: " + file.getName());

        // 1. workspace准备（classpath）
        String classpath;
        try {
            classpath = ensureWorkspace();
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Maven workspace准备失败: " + e.getMessage());
            TestResult r = new TestResult();
            r.mergeResults(Map.of("maven", new TestOutput("", e.getMessage(), -1)));
            return r;
        }

        Path tmpOut = null;
        try {
            // 2. 编译增强用例
            tmpOut = Files.createTempDirectory("llm4test-mvn-");
            TestOutput compileOutput = compile(file.toPath(), tmpOut, classpath);
            if (compileOutput != null) {
                // 编译失败：Compilation failed 语义与JDK模式对齐
                TestResult r = new TestResult();
                r.mergeResults(Map.of("maven", compileOutput));
                LoggerUtil.logExec(Level.INFO, "Maven测试编译失败: " + file.getName());
                return r;
            }

            // 3. junit console 执行
            String fqn = deriveFullyQualifiedName(file.toPath());
            TestOutput output = runJUnitConsole(fqn, tmpOut, classpath);

            TestResult result = new TestResult();
            result.mergeResults(Map.of("maven", output));
            LoggerUtil.logExec(Level.INFO, "Maven测试执行完成: " + file.getName()
                    + " exitValue=" + output.exitValue);
            return result;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Maven测试执行异常: " + e.getMessage());
            TestResult r = new TestResult();
            r.mergeResults(Map.of("maven", new TestOutput("", e.getMessage(), -1)));
            return r;
        } finally {
            deleteRecursively(tmpOut);
        }
    }

    /**
     * 编译测试源码；失败返回携带 "Compilation failed" 语义的 TestOutput，成功返回null
     */
    private TestOutput compile(Path source, Path outputDir, String classpath) throws Exception {
        List<String> command = List.of(
                javacBinary(), "-encoding", "UTF-8", "-parameters",
                "-cp", classpath,
                "-d", outputDir.toString(),
                source.toString());
        ProcessOutput po = runProcess(command, EXECUTION_TIMEOUT_MS);
        if (po.exitValue() != 0) {
            return new TestOutput("Compilation failed\n" + po.stdout() + po.stderr(),
                    po.stderr(), 2, outputParser());
        }
        return null;
    }

    private TestOutput runJUnitConsole(String fqn, Path compiledClasses, String classpath)
            throws Exception {
        List<String> command = List.of(
                javaBinary(), "-jar", junitConsoleJar,
                "--select-class", fqn,
                "-cp", compiledClasses + File.pathSeparator + classpath,
                "--disable-ansi-colors",
                "--fail-if-no-tests",
                "--details=tree");
        ProcessOutput po = runProcess(command, EXECUTION_TIMEOUT_MS);
        String stdout = po.stdout();
        String stderr = po.stderr();
        int exit = po.timedOut() ? 124 : po.exitValue();
        if (po.timedOut()) {
            stderr = stderr + "\n[TIMEOUT after " + EXECUTION_TIMEOUT_MS + " ms]";
        }
        return new TestOutput(stdout, stderr, exit, outputParser());
    }

    /**
     * 从源码推断全限定类名（package声明 + 文件名）
     */
    static String deriveFullyQualifiedName(Path source) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8);
        String pkg = "";
        var matcher = java.util.regex.Pattern.compile(
                "(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(content);
        if (matcher.find()) {
            pkg = matcher.group(1);
        }
        String cls = source.getFileName().toString().replaceAll("\\.java$", "");
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    // ==================== workspace（classpath缓存） ====================

    /**
     * 准备并缓存项目classpath：target/classes + target/test-classes + 依赖classpath。
     * pom.xml 比 classpath.txt 新时自动重建。
     * test-compile 失败（仓库自带测试编译不过）时降级为 compile，仅用主代码classpath。
     */
    String ensureWorkspace() throws Exception {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }
        Path root = Paths.get(projectRoot).toAbsolutePath().normalize();
        Path pom = root.resolve("pom.xml");
        Path marker = root.resolve(WORKSPACE_DIR).resolve("classpath.txt");
        Path classes = root.resolve("target/classes");
        Path testClasses = root.resolve("target/test-classes");

        boolean stale = !Files.exists(marker)
                || !Files.exists(classes)
                || Files.getLastModifiedTime(pom).toMillis() > Files.getLastModifiedTime(marker).toMillis();
        if (stale) {
            LoggerUtil.logExec(Level.INFO, "Maven workspace构建: mvn test-compile + dependency:build-classpath");
            Files.createDirectories(root.resolve(WORKSPACE_DIR));
            List<String> baseCommand = List.of(
                    "mvn", "-q", "-f", pom.toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + marker);
            // 先尝试带 test-compile；失败降级为仅 compile（仓库自带测试编译不过的场景）
            List<String> withTests = new ArrayList<>(baseCommand);
            withTests.add(1, "test-compile");
            ProcessOutput po = runProcess(withTests, BUILD_TIMEOUT_MS);
            if (po.exitValue() != 0) {
                LoggerUtil.logExec(Level.WARNING,
                        "mvn test-compile 失败，降级为 compile（test-classes 不在classpath中）:\n"
                                + truncate(po.stdout() + po.stderr(), 2000));
                List<String> mainOnly = new ArrayList<>(baseCommand);
                mainOnly.add(1, "compile");
                po = runProcess(mainOnly, BUILD_TIMEOUT_MS);
            }
            if (po.exitValue() != 0) {
                throw new IllegalStateException("mvn构建失败(exit=" + po.exitValue() + "):\n"
                        + truncate(po.stdout() + po.stderr(), 2000));
            }
        }

        String deps = Files.readString(marker, StandardCharsets.UTF_8).trim();
        StringBuilder cp = new StringBuilder();
        if (Files.exists(classes)) cp.append(classes);
        if (Files.exists(testClasses)) cp.append(File.pathSeparator).append(testClasses);
        if (!deps.isEmpty()) cp.append(File.pathSeparator).append(deps);

        cachedClasspath = cp.toString();
        LoggerUtil.logExec(Level.INFO, "Maven workspace就绪: classpath=" + cachedClasspath);
        return cachedClasspath;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ==================== 环境 ====================

    @Override
    public void checkEnvironment() {
        LoggerUtil.logExec(Level.INFO, "检查执行环境 (maven)...");
        checkCommandAvailable("mvn", "-version", 30);
        if (!Files.exists(Paths.get(projectRoot, "pom.xml"))) {
            LoggerUtil.logExec(Level.WARNING, "项目根目录缺少pom.xml: " + projectRoot
                    + "（请配置 project.root 指向Maven仓库）");
        }
        if (!Files.exists(Paths.get(junitConsoleJar))) {
            LoggerUtil.logExec(Level.WARNING, "junit-platform-console-standalone 不存在: " + junitConsoleJar
                    + "（请下载到该路径或配置 maven.junitConsoleJar）");
        }
        if (!Files.isDirectory(testSourceRoot)) {
            LoggerUtil.logExec(Level.WARNING, "测试源码目录不存在: " + testSourceRoot);
        }
        LoggerUtil.logExec(Level.INFO, "环境检查完成");
    }

    @Override
    public void verifyEnvironment() {
        LoggerUtil.logExec(Level.INFO, "开始测试Maven环境...");
        checkCommandAvailable("mvn", "-version", 60);
        checkCommandAvailable(javaBinary(), "-version", 60);
        LoggerUtil.logExec(Level.INFO, "Maven环境测试完成");
    }

    private void checkCommandAvailable(String cmd, String arg, int timeoutSec) {
        try {
            ProcessRunner.Result result = ProcessRunner.run(
                    List.of(cmd, arg), TimeUnit.SECONDS.toMillis(timeoutSec));
            if (result.timedOut()) {
                LoggerUtil.logExec(Level.SEVERE, cmd + " 检查超时");
            } else if (result.exitValue() == 0) {
                LoggerUtil.logExec(Level.INFO, cmd + " 命令检查通过");
            } else {
                LoggerUtil.logExec(Level.WARNING, cmd + " 返回非零退出码: " + result.exitValue());
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, cmd + " 不可用: " + e.getMessage());
        }
    }

    // ==================== 进程辅助 ====================

    private record ProcessOutput(String stdout, String stderr, int exitValue, boolean timedOut) {
    }

    /** 同步执行外部进程并收集输出（并发消费流 + 超时 + 终止进程树见 {@link ProcessRunner}） */
    private ProcessOutput runProcess(List<String> command, long timeoutMs) throws Exception {
        LoggerUtil.logExec(Level.INFO, "执行进程: " + String.join(" ", command));
        ProcessRunner.Result result = ProcessRunner.run(command, Map.of("LANG", "en_US.UTF-8"), timeoutMs);
        return new ProcessOutput(result.stdout(), result.stderr(), result.exitValue(), result.timedOut());
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        return javaHome != null ? Paths.get(javaHome, "bin", "java").toString() : "java";
    }

    private static String javacBinary() {
        String javaHome = System.getProperty("java.home");
        return javaHome != null ? Paths.get(javaHome, "bin", "javac").toString() : "javac";
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 清理失败不影响结果
        }
    }
}