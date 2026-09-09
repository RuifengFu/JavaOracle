package edu.tju.ista.llm4test.pilot;

import edu.tju.ista.llm4test.adapter.maven.MavenProjectAdapter;
import edu.tju.ista.llm4test.config.ConfigUtil;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实第三方仓库 pilot：apache/commons-lang。
 * <p>
 * 目标是回答「这个项目能不能对上一个真实 Maven 库」这三件事：
 * <ol>
 *   <li><b>找到它的测试</b> —— 适配器发现仓库自带的 JUnit 用例</li>
 *   <li><b>找到它的 API</b> —— 从库自身源码注释里提取 javadoc（不是 JDK 的）</li>
 *   <li><b>执行它的测试</b> —— 走 javac + junit-console 链路并正确分类</li>
 * </ol>
 * 仓库位置按以下顺序解析（都支持相对路径，相对本项目工作目录）：
 * 环境变量 {@code PILOT_MAVEN_REPO} → 配置项 {@code pilot.mavenRepo} →
 * 缺省的同级检出 {@code ../commons-lang}；不存在则整类跳过（不影响 CI）。
 * <pre>
 * cd .. &amp;&amp; git clone --depth 1 https://github.com/apache/commons-lang.git
 * </pre>
 */
class CommonsLangPilotTest {

    private static final String CONSOLE_JAR = "Dependency/junit-platform-console-standalone-1.11.4.jar";

    private static Path repo;
    private static MavenProjectAdapter adapter;

    @TempDir
    Path tempDir;

    /** 同级检出：本项目与被测库并列，避免把仓库位置写死成某台机器的绝对路径 */
    private static final String DEFAULT_PILOT_REPO = "../commons-lang";

    @BeforeAll
    static void locateRepo() {
        repo = Path.of(firstNonBlank(
                System.getenv("PILOT_MAVEN_REPO"),
                ConfigUtil.get("pilot.mavenRepo"),
                DEFAULT_PILOT_REPO));

        Assumptions.assumeTrue(Files.isDirectory(repo.resolve("src/test/java")),
                "commons-lang 未克隆到 " + repo + "，跳过 pilot");
        Assumptions.assumeTrue(Files.exists(Path.of(CONSOLE_JAR)),
                "junit-console jar 缺失，跳过 pilot");

        adapter = new MavenProjectAdapter(repo.toString(), CONSOLE_JAR);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        throw new IllegalStateException("无可用的 pilot 仓库路径");
    }

    /** 库自身源码根（包根平铺，无 JDK 的 module/platform/classes 层级） */
    private static String librarySourceRoot() {
        return repo.resolve("src/main/java").toString();
    }

    // ==================== 1. 找到它的测试 ====================

    @Test
    void discoversLibraryOwnTests() {
        List<String> tests = adapter.discoverTests(".");

        assertTrue(tests.size() > 300, "commons-lang 应发现数百个测试类，实际: " + tests.size());
        assertTrue(tests.contains("org/apache/commons/lang3/StringUtilsTest.java"),
                "应包含 StringUtilsTest");
        assertTrue(tests.contains("org/apache/commons/lang3/CharUtilsTest.java"));
        assertTrue(tests.stream().allMatch(t -> t.endsWith(".java")));
        assertTrue(tests.stream().noneMatch(t -> t.startsWith("/")),
                "发现结果应是相对测试根的路径: " + tests.stream().filter(t -> t.startsWith("/")).toList());

        // 按子包收窄
        List<String> scoped = adapter.discoverTests("org/apache/commons/lang3/math");
        assertFalse(scoped.isEmpty(), "math 子包应有测试");
        assertTrue(scoped.stream().allMatch(t -> t.startsWith("org/apache/commons/lang3/math/")),
                "子包发现结果仍应相对测试根: " + scoped);

        File resolved = adapter.resolveTestFile("org/apache/commons/lang3/CharUtilsTest.java");
        assertTrue(resolved.exists(), "resolveTestFile 应还原为真实文件: " + resolved);
    }

    // ==================== 2. 找到它的 API ====================

    @Test
    void extractsLibraryApiDocsFromItsOwnSource() throws Exception {
        // 库源码根平铺，故 defaultSourcePrefix 为空串；baseDocPath 为 null（无 HTML 文档）
        ApiInfoProcessor processor = new ApiInfoProcessor(null, librarySourceRoot(), "");

        Path probe = tempDir.resolve("StringUtilsProbe.java");
        Files.writeString(probe, """
                import org.apache.commons.lang3.StringUtils;
                public class StringUtilsProbe {
                    public static void main(String[] args) {
                        String s = StringUtils.substring("abcdef", 2, 4);
                        boolean e = StringUtils.isEmpty(s);
                        System.out.println(s + e);
                    }
                }
                """);

        Map<String, String> docs = processor.getApiDocWithSource(probe.toFile());

        String substring = docs.get("StringUtils.substring");
        assertNotNull(substring, "应定位到库自身的 StringUtils.substring，实际键: " + docs.keySet());
        assertNotNull(docs.get("StringUtils.isEmpty"), "实际键: " + docs.keySet());

        // 符号解析要拿到带参数类型的完整签名，而不是退化成简单名
        assertTrue(substring.contains("org.apache.commons.lang3.StringUtils.substring(java.lang.String, int, int)"),
                "应解析出库 API 的完整签名: " + substring);
        // 指向库自身源码文件，而不是 JDK 的 String.java
        assertTrue(substring.contains("/src/main/java/org/apache/commons/lang3/StringUtils.java"),
                "应指向库源码文件: " + substring);
        // oracle 判定 API 是否正确要靠这段契约文本（javadoc 描述 + 行为表）
        assertTrue(substring.contains("Gets a substring from the specified String avoiding exceptions"),
                "应提取库自身的 javadoc 描述: " + substring);
        assertTrue(substring.contains("StringUtils.substring(\"abc\", 2, 4)"),
                "应保留 javadoc 里的行为示例（契约证据）: " + substring);
        assertTrue(substring.contains("=== 方法源码 ==="), "应带上方法实现");

        // 类级文档同样来自库源码注释
        assertNotNull(docs.get("StringUtils_CLASS_DOC"), "应提取类级文档，实际键: " + docs.keySet());
    }

    @Test
    void extractsApiDocsFromRealRepositoryTestFile() throws Exception {
        ApiInfoProcessor processor = new ApiInfoProcessor(null, librarySourceRoot(), "");
        File realTest = adapter.resolveTestFile("org/apache/commons/lang3/CharUtilsTest.java");

        Map<String, String> docs = processor.getApiDocWithSource(realTest);

        assertFalse(docs.isEmpty(), "真实用例应能抽出 API 信息");
        assertTrue(docs.keySet().stream().anyMatch(k -> k.startsWith("CharUtils.")),
                "应包含被测库自身 API（CharUtils.*），实际键: " + docs.keySet());
    }

    // ==================== 3. 执行它的测试 ====================

    @Test
    void executesLibraryOwnTestThroughAdapter() {
        TestCase tc = new TestCase(adapter.resolveTestFile("org/apache/commons/lang3/CharUtilsTest.java"));

        TestResult result = adapter.executeTest(tc);

        assertEquals(TestResultKind.SUCCESS, result.getKind(),
                "commons-lang 自带的 CharUtilsTest 应通过。输出:\n" + result.getOutput());
        assertFalse(result.getCompilationFailed(), "不应有编译失败");
    }
}
