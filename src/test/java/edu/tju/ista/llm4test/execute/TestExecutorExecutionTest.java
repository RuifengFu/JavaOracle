package edu.tju.ista.llm4test.execute;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestExecutor 真实执行回归测试（依赖 jtreg、jdk17u-dev 与 config jdkPaths，缺失则跳过）。
 * 锁定行为：通过→SUCCESS；断言失败→TEST_FAIL；编译失败→TEST_FAIL+compilationFailed。
 */
class TestExecutorExecutionTest {

    @TempDir
    Path tempDir;

    static boolean envReady;

    @BeforeAll
    static void checkEnv() {
        boolean treeOk = Files.exists(Path.of("jdk17u-dev/test/jdk/java/lang/Integer/Unsigned.java"));
        boolean jdkOk = !edu.tju.ista.llm4test.config.GlobalConfig.getJdkPaths().isEmpty()
                && new File(edu.tju.ista.llm4test.config.GlobalConfig.getJdkPaths().get(0)).isDirectory();
        envReady = treeOk && jdkOk && jtregAvailable();
        Assumptions.assumeTrue(envReady, "jtreg/JDK环境不完整，跳过执行测试");
    }

    @Test
    void passingTestClassifiedSuccess() {
        TestExecutor executor = new TestExecutor();
        try {
            File test = new File("jdk17u-dev/test/jdk/java/lang/Integer/Unsigned.java");
            TestResult result = executor.executeTest(test);
            assertNotNull(result);
            assertEquals(TestResultKind.SUCCESS, result.getKind(),
                    "通过用例应为SUCCESS: " + result);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void assertionFailureClassifiedTestFail() throws Exception {
        File failTest = writeTest("FailTest", """
                /* @test
                 * @run main FailTest
                 */
                public class FailTest {
                    public static void main(String[] args) throws Exception {
                        throw new AssertionError("boom-expected-failure");
                    }
                }
                """);
        TestExecutor executor = new TestExecutor();
        try {
            TestResult result = executor.executeTest(failTest);
            assertEquals(TestResultKind.TEST_FAIL, result.getKind());
            assertFalse(result.getCompilationFailed());
            assertTrue(result.getOutput().contains("boom-expected-failure"),
                    "输出应包含异常信息: " + result.getOutput());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void compileFailureSetsCompilationFlag() throws Exception {
        File badTest = writeTest("CompileFail", """
                /* @test
                 * @run main CompileFail
                 */
                public class CompileFail {
                    public static void main(String[] args) {
                        this is not valid java
                    }
                }
                """);
        TestExecutor executor = new TestExecutor();
        try {
            TestResult result = executor.executeTest(badTest);
            assertTrue(List.of(TestResultKind.TEST_FAIL, TestResultKind.COMPILE_FAIL).contains(result.getKind()),
                    "编译失败应为失败类: " + result.getKind());
            assertTrue(result.getCompilationFailed(), "应标记compilationFailed");
        } finally {
            executor.shutdown();
        }
    }

    /** 在临时套件目录写jtreg测试（需TEST.ROOT标记） */
    private File writeTest(String name, String content) throws Exception {
        Path suite = tempDir.resolve("suite" + System.nanoTime());
        Files.createDirectories(suite);
        Files.writeString(suite.resolve("TEST.ROOT"), "# mini suite\n");
        Path f = suite.resolve(name + ".java");
        Files.writeString(f, content);
        return f.toFile();
    }

    private static boolean jtregAvailable() {
        try {
            Process p = new ProcessBuilder("jtreg", "-version").redirectErrorStream(true).start();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}