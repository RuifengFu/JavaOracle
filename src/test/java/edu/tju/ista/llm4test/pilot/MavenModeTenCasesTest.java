package edu.tju.ista.llm4test.pilot;

import edu.tju.ista.llm4test.adapter.maven.MavenProjectAdapter;
import edu.tju.ista.llm4test.config.ConfigUtil;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Maven 模式十用例验收：全部经 Agent 面向的执行工具
 * （{@code createExecuteTool()}）跑，不经 LLM。
 * <p>
 * 覆盖两类入口与四种分类：
 * <ul>
 *   <li>仓库自带用例走文件路径入口（{@code is_file_path=true}，BugVerify 那条重载）</li>
 *   <li>增强用例走源码字符串入口（{@code is_file_path=false}，HypothesisAgent 那条重载）</li>
 *   <li>分类：SUCCESS / TEST_FAIL(断言失败) / TEST_FAIL(编译失败) / TEST_FAIL(无用例)</li>
 * </ul>
 * 仓库缺失则整类跳过，不影响 CI。
 */
class MavenModeTenCasesTest {

    private static final String CONSOLE_JAR = "Dependency/junit-platform-console-standalone-1.11.4.jar";
    private static final String DEFAULT_PILOT_REPO = "../commons-lang";

    @TempDir
    Path tempDir;

    private static MavenProjectAdapter adapter;
    private static TestExecuteTool tool;

    /** 仓库自带的用例（覆盖多个子包），走文件路径入口 */
    private static final List<String> REPO_TESTS = List.of(
            "org/apache/commons/lang3/CharSequenceUtilsTest.java",
            "org/apache/commons/lang3/StringUtilsTrimTest.java",
            "org/apache/commons/lang3/ValidateTest.java",
            "org/apache/commons/lang3/builder/DiffTest.java",
            "org/apache/commons/lang3/concurrent/LazyInitializerCloserTest.java",
            "org/apache/commons/lang3/function/TriConsumerTest.java");

    @BeforeAll
    static void setUp() {
        String configured = System.getenv("PILOT_MAVEN_REPO");
        if (configured == null || configured.isBlank()) {
            configured = ConfigUtil.get("pilot.mavenRepo");
        }
        Path repo = Path.of(configured == null || configured.isBlank() ? DEFAULT_PILOT_REPO : configured);
        Assumptions.assumeTrue(Files.isDirectory(repo.resolve("src/test/java")),
                "commons-lang 未克隆到 " + repo + "，跳过");
        Assumptions.assumeTrue(Files.exists(Path.of(CONSOLE_JAR)), "junit-console jar 缺失，跳过");
        adapter = new MavenProjectAdapter(repo.toString(), CONSOLE_JAR);
        tool = adapter.createExecuteTool();
    }

    @Test
    @DisplayName("6 个仓库自带用例经文件路径入口执行")
    void repositoryTestsPassThroughFilePathEntry() {
        List<String> failures = new ArrayList<>();
        for (String relative : REPO_TESTS) {
            Path file = adapter.resolveTestFile(relative).toPath();
            assertTrue(Files.exists(file), "用例文件应存在: " + file);

            ToolResponse<TestResult> response = tool.execute(file, fileClassName(relative));
            if (!response.isSuccess()) {
                failures.add(relative + " -> 工具执行失败: " + response.getResult());
                continue;
            }
            TestResult result = response.getResult();
            if (result.getKind() != TestResultKind.SUCCESS) {
                failures.add(relative + " -> " + result.getKind() + "\n" + result.getOutput());
            }
        }
        assertTrue(failures.isEmpty(), "以下用例未通过:\n" + String.join("\n---\n", failures));
    }

    @Test
    @DisplayName("增强用例（源码字符串）：契约相符判 SUCCESS")
    void enhancedCaseMatchingContractSucceeds() {
        TestResult result = runSource("""
                package org.apache.commons.lang3;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;
                public class TenCasesContractOkTest {
                    @Test
                    void stripHonoursDoc() {
                        assertNull(StringUtils.strip(null));
                        assertEquals("abc", StringUtils.strip("  abc  "));
                        assertEquals("", StringUtils.strip(""));
                    }
                }
                """);
        assertEquals(TestResultKind.SUCCESS, result.getKind(), result.getOutput());
        assertFalse(result.getCompilationFailed());
    }

    @Test
    @DisplayName("增强用例：违反契约判 TEST_FAIL 且标签正确")
    void enhancedCaseViolatingContractFails() {
        TestResult result = runSource("""
                package org.apache.commons.lang3;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class TenCasesContractViolationTest {
                    @Test
                    void stripDoesNotReturnWhatDocSays() {
                        assertEquals("WRONG", StringUtils.strip("  abc  "));
                    }
                }
                """);
        assertEquals(TestResultKind.TEST_FAIL, result.getKind(), result.getOutput());
        assertFalse(result.getCompilationFailed(), "断言失败不是编译失败");
        assertTrue(result.getOutput().contains("(TEST_FAIL)"),
                "退出码标签应为 TEST_FAIL 而不是 UNKNOWN: " + result.getOutput());
    }

    @Test
    @DisplayName("增强用例：编译错误判编译失败")
    void enhancedCaseWithSyntaxErrorIsCompileFailure() {
        TestResult result = runSource("""
                package org.apache.commons.lang3;
                import org.junit.jupiter.api.Test;
                public class TenCasesBrokenSyntaxTest {
                    @Test
                    void broken() {
                        this is not java;
                    }
                }
                """);
        assertTrue(result.isFail(), "编译错误应判失败: " + result.getKind());
        assertTrue(result.getCompilationFailed(), "应标记 compilationFailed");
    }

    @Test
    @DisplayName("增强用例：类里没有 @Test 时按无用例处理")
    void enhancedCaseWithoutTestsIsReported() {
        TestResult result = runSource("""
                package org.apache.commons.lang3;
                public class TenCasesNoTestsTest {
                    void notATest() {
                        StringUtils.strip("  abc  ");
                    }
                }
                """);
        assertTrue(result.isFail(), "--fail-if-no-tests 应判失败: " + result.getKind());
        assertTrue(result.getOutput().contains("COMPILE_FAIL_OR_NO_TESTS"),
                "退出码 2 的标签应指出可能是编译失败或没有用例: " + result.getOutput());
    }

    // ==================== 5. 多文件用例 ====================

    /**
     * 多文件用例：主测试类 + 辅助类分别成文件，两者一起编译，只有带 @Test 的被选中执行。
     * 模拟 {@code write_test_file} 被调用两次的产物。
     */
    @Test
    void multiFileTestCaseCompilesHelperAndRunsOnlyTestClass() throws Exception {
        Path caseDir = tempDir.resolve("org/apache/commons/lang3");
        Files.createDirectories(caseDir);

        Path mainTest = caseDir.resolve("MultiFileProbeTest.java");
        Files.writeString(mainTest, """
                package org.apache.commons.lang3;

                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class MultiFileProbeTest {
                    @Test
                    void usesHelperToProbeStringUtils() {
                        // 断言依据仍是 StringUtils.strip 的 javadoc 契约
                        assertEquals("abc", StringUtils.strip(ProbeFixture.padded()));
                        assertEquals(ProbeFixture.expected(), StringUtils.strip("  abc  "));
                    }
                }
                """);

        Path helper = caseDir.resolve("ProbeFixture.java");
        Files.writeString(helper, """
                package org.apache.commons.lang3;

                /** 辅助类：没有 @Test，只应参与编译 */
                public class ProbeFixture {
                    public static String padded() { return "   abc   "; }
                    public static String expected() { return "abc"; }
                }
                """);

        TestCase tc = new TestCase(mainTest.toFile());
        tc.recordWrittenFiles(java.util.List.of(mainTest.toFile(), helper.toFile()));
        assertEquals(1, tc.getCompanionFiles().size(), "辅助类应记为伴随文件");
        assertEquals(2, tc.getAllSourceFiles().size());

        TestResult result = adapter.executeTest(tc);

        assertEquals(TestResultKind.SUCCESS, result.getKind(),
                "主类引用辅助类应能编译并通过。输出:\n" + result.getOutput());
        assertFalse(result.getCompilationFailed(), "辅助类应被一起编译，不该编译失败");
    }

    /** 伴随文件里也可以有测试类：两个类的 @Test 都要跑到 */
    @Test
    void multiFileTestCaseRunsTestsFromEveryFile() throws Exception {
        Path caseDir = tempDir.resolve("org/apache/commons/lang3");
        Files.createDirectories(caseDir);

        Path first = caseDir.resolve("PairOneTest.java");
        Files.writeString(first, """
                package org.apache.commons.lang3;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                public class PairOneTest {
                    @Test
                    void isEmptyContract() { assertTrue(StringUtils.isEmpty("")); }
                }
                """);
        Path second = caseDir.resolve("PairTwoTest.java");
        Files.writeString(second, """
                package org.apache.commons.lang3;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                public class PairTwoTest {
                    @Test
                    void isEmptyIsFalseForBlank() { assertFalse(StringUtils.isEmpty(" ")); }
                }
                """);

        TestCase tc = new TestCase(first.toFile());
        tc.recordWrittenFiles(java.util.List.of(first.toFile(), second.toFile()));

        TestResult result = adapter.executeTest(tc);

        assertEquals(TestResultKind.SUCCESS, result.getKind(), result.getOutput());
        // --details=tree 会把两个类都列出来
        assertTrue(result.getOutput().contains("PairOneTest"), "应执行第一个测试类: " + result.getOutput());
        assertTrue(result.getOutput().contains("PairTwoTest"), "伴随文件里的测试类也应被执行: " + result.getOutput());
    }

    private TestResult runSource(String source) {
        ToolResponse<TestResult> response = tool.execute(source);
        assertTrue(response.isSuccess(), "工具本身应执行成功: " + response.getResult());
        return response.getResult();
    }

    private static String fileClassName(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".java".length());
    }
}
