package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 目标项目适配器（SPI）：封装与"被测项目形态"相关的全部细节——
 * 测试发现、测试执行、环境检查。
 * <p>
 * 现有实现：
 * <ul>
 *   <li>{@link edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter} —— JDK/jtreg 场景（永久保留）</li>
 *   <li>（规划）MavenProjectAdapter —— 第三方 Maven 仓库的 JUnit 测试增强</li>
 * </ul>
 * <p>
 * 约定：适配器只负责"执行并分类结果"（SUCCESS/COMPILE_FAIL/TEST_FAIL/EXECUTE_TIMEOUT等），
 * "真bug vs 测试问题"的裁决（VERIFIED_BUG）统一由上层 LLM 流程完成。
 */
public interface ProjectAdapter extends HarnessExitSemantics {

    /** 适配器标识，如 "jdk"、"maven" */
    String id();

    // ==================== 测试发现 ====================

    /**
     * 发现测试用例。
     * @param rootPath 用户输入的目标路径（目录或单个文件，语义同 Main execute/generate 参数）
     * @return 套件相对路径标识列表（后续可用 {@link #resolveTestFile} 还原为文件）
     */
    List<String> discoverTests(String rootPath);

    /**
     * 将 discoverTests 返回的相对路径标识还原为实际测试文件
     */
    File resolveTestFile(String relativeTestPath);

    /**
     * 测试文件有效性检查（默认按 {@code maxFileSize} 兜底护栏过滤）。
     * <p>
     * 超限会记 WARNING：这个阈值曾经按 jtreg 用例（普遍很小）定得很紧，
     * 第三方库的测试文件大得多，静默过滤会让「一个用例都没发现」变得无从排查。
     */
    default boolean isValidTest(File test) {
        try {
            if (!test.exists()) {
                return false;
            }
            long limit = GlobalConfig.getMaxFileSize();
            if (test.length() > limit) {
                LoggerUtil.logExec(java.util.logging.Level.WARNING,
                        "跳过超出 maxFileSize(" + limit + ") 的测试文件: "
                                + test.getPath() + " (" + test.length() + " 字节)");
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 工作区与路径模型 ====================

    /**
     * 套件根：{@link #discoverTests} 返回的相对路径、以及通过列表（缓存文件）中的条目
     * 都相对于它；{@code CommandHandler} 也用它给用户输入的路径加前缀。
     * <p>
     * JDK: {@code jdk17u-dev/test/jdk/}；Maven: {@code <repo>/src/test/java}。
     */
    String suiteRoot();

    /**
     * 把用户输入的目标路径（execute/generate 的参数）拼到套件根之下。
     * <p>
     * join 逻辑集中在这里：各适配器的 {@link #suiteRoot} 是否带尾斜杠不一致
     * （JDK 的 {@code suiteBasePath} 带、Maven 的测试源根不带），调用方不该关心。
     * 绝对路径与空输入原样透传。
     */
    default String resolveSuitePath(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            return suiteRoot();
        }
        java.nio.file.Path p = java.nio.file.Path.of(userPath);
        if (p.isAbsolute()) {
            return userPath;
        }
        return java.nio.file.Path.of(suiteRoot()).resolve(userPath).toString();
    }

    /**
     * 工作区源根：整棵被复制到 {@code testDir} 的树。
     * 增强/修复流程会改写用例，因此执行的是可写副本而不是上游检出。
     * <p>
     * 默认与 {@link #suiteRoot} 相同；JDK 的复制根比套件根高一层（{@code jdk17u-dev/test}，
     * 含 {@code jdk/} 子目录），故单独覆盖。
     */
    default String workspaceSourceRoot() {
        return suiteRoot();
    }

    /**
     * 准备可写工作区：把 {@link #workspaceSourceRoot} 整棵复制到 {@code resultDir}。
     * 无需副本的适配器可覆盖为空实现。
     */
    default void prepareWorkspace(File resultDir) {
        java.nio.file.Path source = java.nio.file.Path.of(workspaceSourceRoot());
        if (!java.nio.file.Files.isDirectory(source)) {
            // FileProcessor 里是 Objects.requireNonNull(listFiles())，目录不存在会抛裸 NPE，
            // 排查起来毫无线索（Maven 模式下 project.root 配错就是这个症状）
            throw new IllegalStateException("工作区源目录不存在: " + source.toAbsolutePath()
                    + "（适配器 " + id() + "；请检查 project.root / jdkTestPath 配置）");
        }
        new edu.tju.ista.llm4test.utils.FileProcessor(resultDir).copyTestFiles(source);
    }

    /**
     * 原始用例文件 → 工作区副本路径。
     * 不在 {@link #workspaceSourceRoot} 之下时原样返回（与历史的字符串替换语义一致）。
     */
    default File toWorkspaceFile(File originFile) {
        try {
            java.nio.file.Path srcRoot = java.nio.file.Path.of(workspaceSourceRoot())
                    .toAbsolutePath().normalize();
            java.nio.file.Path origin = originFile.toPath().toAbsolutePath().normalize();
            java.nio.file.Path rel = srcRoot.relativize(origin);
            if (rel.toString().isEmpty() || rel.startsWith("..")) {
                // 调用方拿到这个文件之后会**写**它（removeHeader / 写回增强代码），
                // 原样返回等于直接改上游检出。保持历史行为但必须留下告警。
                LoggerUtil.logExec(java.util.logging.Level.WARNING,
                        "用例不在工作区源根之下，将直接就地修改上游文件: " + originFile
                                + "（源根 " + srcRoot + "）");
                return originFile;
            }
            return java.nio.file.Path.of(GlobalConfig.getTestDir())
                    .toAbsolutePath().normalize().resolve(rel).toFile();
        } catch (Exception e) {
            return originFile;
        }
    }

    // ==================== 测试执行 ====================

    /**
     * 执行单个测试用例并返回分类结果。
     * JDK实现为多JDK差分执行；其他实现为单环境执行。
     */
    TestResult executeTest(TestCase testCase);

    /**
     * 本 harness 的输出解析器。
     * <p>
     * 让 {@code execute.TestOutput} 能按当前适配器解析，而不必 import 某个具体
     * 适配器的实现——核心层反向依赖 {@code adapter.jdk} 会让 Maven 模式下
     * core 路径静默拿到 jtreg 解析。
     */
    HarnessOutputParser outputParser();

    /**
     * 清理本适配器的临时工作区（默认不做事）。
     * <p>
     * 放在接口上是为了让门面不必 {@code instanceof} 向下转型到具体适配器。
     */
    default void cleanupWorkspace() {
    }

    /**
     * 这段源码看起来是否本 harness 的测试（用于决定能否交给执行工具跑）。
     * <p>
     * 默认按 JUnit 约定认 {@code @Test}。HypothesisAgent 历史上直接判
     * {@code code.contains("@test")}——那是 jtreg 的小写标记，JUnit 代码永远不含，
     * 于是 Maven 模式下每个假设都掉进它自己那套 JDK 取向的编译执行回退路径。
     */
    default boolean looksLikeHarnessTest(String sourceCode) {
        return sourceCode != null && sourceCode.contains("@Test");
    }

    /**
     * 提供本 harness 的测试执行工具（LLM 工具）。
     * <p>
     * Agent 由此获得执行能力，不再自己 {@code new JtregExecuteTool()}——那是
     * Maven 模式下 agent 工作流不可用的根因。工具名请取自
     * {@code createExecuteTool().getName()}，它与 {@code harnessDirectives()}
     * 里的 {@code executeToolName} 必须一致（有测试锁住）。
     */
    TestExecuteTool createExecuteTool();

    // ==================== harness 说明（注入prompt模板） ====================

    /**
     * harness 指令片段：由 {@code PromptGen} 自动注入模板的 {@code ${harness.*}} 变量，
     * 使增强/修复/最小化等提示词与具体测试框架解耦。
     * <p>
     * 约定键：
     * <ul>
     *   <li>{@code name} —— 框架名（如 jtreg / JUnit 5）</li>
     *   <li>{@code tagList} —— 必须保留的标记列表文案</li>
     *   <li>{@code tagExample} —— 标记示例代码块</li>
     *   <li>{@code executeToolName} —— 执行工具名（LLM工具调用）</li>
     * </ul>
     */
    default Map<String, String> harnessDirectives() {
        // 默认按 JUnit 5 约定
        Map<String, String> directives = new java.util.HashMap<>();
        directives.put("name", "JUnit 5");
        directives.put("tagList", "(`@Test`, `@BeforeEach`, `@DisplayName`, ...)");
        directives.put("tagExample", """
                ```
                @Test
                void enhancedBehavior() {
                    // assertions
                }
                ```""");
        // 从工具本身取名：写死默认值的话，新适配器忘了覆盖就会让 prompt 指向一个
        // 没注册的工具名，且这种漂移只有在真跑 LLM 时才暴露
        directives.put("executeToolName", createExecuteTool().getName());
        return directives;
    }

    // ==================== 环境 ====================

    /** 轻量环境检查（执行器初始化时调用，仅打印告警不抛异常） */
    void checkEnvironment();

    /** 环境自检（env 命令入口：实际跑一遍目标运行时） */
    void verifyEnvironment();
}