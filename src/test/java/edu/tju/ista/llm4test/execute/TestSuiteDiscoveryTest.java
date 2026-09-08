package edu.tju.ista.llm4test.execute;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestSuite 测试发现回归测试（依赖 jtreg 与 jdk17u-dev，缺失则跳过）。
 */
class TestSuiteDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversTestsViaJtreg() {
        Assumptions.assumeTrue(Files.exists(Path.of("jdk17u-dev/test/jdk/java/lang/Integer/Unsigned.java")),
                "jdk17u-dev 源码树不存在，跳过");
        Assumptions.assumeTrue(isJtregAvailable(), "jtreg 不可用，跳过");

        TestSuite suite = new TestSuite("jdk17u-dev/test/jdk/java/lang/Integer");
        var tests = suite.getTestFiles();
        assertFalse(tests.isEmpty(), "应发现至少一个测试");
        assertTrue(tests.stream().anyMatch(t -> t.getPath().endsWith("Unsigned.java")),
                "应包含Unsigned.java: " + tests);
        assertTrue(tests.stream().allMatch(t -> t.getPath().endsWith(".java")),
                "发现结果应只含.java文件");
    }

    @Test
    void isValidTestFileChecksSize() throws Exception {
        TestSuite suite = new TestSuite("jdk17u-dev/test/jdk/java/lang/Integer");

        File small = tempDir.resolve("Small.java").toFile();
        Files.writeString(small.toPath(), "/* @test */ public class Small {}");
        assertTrue(suite.isValidTestFile(small));

        File big = tempDir.resolve("Big.java").toFile();
        byte[] bytes = new byte[20000]; // 超过 maxFileSize=10000
        Files.write(big.toPath(), bytes);
        assertFalse(suite.isValidTestFile(big));

        assertFalse(suite.isValidTestFile(tempDir.resolve("NotExist.java").toFile()));
    }

    private static boolean isJtregAvailable() {
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