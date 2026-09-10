package edu.tju.ista.llm4test.adapter.maven;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
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
 * MavenProjectAdapter 集成测试：在临时目录构建真实 Maven fixture 仓库，
 * 走完整的 discover → ensureWorkspace(mvn) → javac → junit-console 链路。
 * 依赖 mvn 与 junit-platform-console-standalone jar，缺失则跳过。
 */
class MavenProjectAdapterTest {

    @TempDir
    Path tempDir;

    static boolean envReady;

    @BeforeAll
    static void checkEnv() {
        boolean mvnOk, jarOk;
        try {
            Process p = new ProcessBuilder("mvn", "-version").redirectErrorStream(true).start();
            mvnOk = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            mvnOk = false;
        }
        jarOk = Files.exists(Path.of("Dependency/junit-platform-console-standalone-1.11.4.jar"));
        envReady = mvnOk && jarOk;
        Assumptions.assumeTrue(envReady, "mvn或junit-console不可用，跳过Maven适配器测试");
    }

    /** 构建fixture仓库：1个主类 + 通过/失败/编译错误三个测试类 */
    private MavenProjectAdapter buildFixtureRepo() throws Exception {
        Path repo = tempDir.resolve("demo-repo");
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.createDirectories(repo.resolve("src/test/java/com/example"));

        Files.writeString(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo-repo</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.11.4</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        Files.writeString(repo.resolve("src/main/java/com/example/Calc.java"), """
                package com.example;
                public class Calc {
                    public static int add(int a, int b) { return a + b; }
                }
                """);

        Files.writeString(repo.resolve("src/test/java/com/example/CalcPassTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class CalcPassTest {
                    @Test
                    void addWorks() {
                        assertEquals(4, Calc.add(2, 2));
                    }
                }
                """);

        Files.writeString(repo.resolve("src/test/java/com/example/CalcFailTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class CalcFailTest {
                    @Test
                    void addIsBroken() {
                        assertEquals(5, Calc.add(2, 2), "boom-expected-failure");
                    }
                }
                """);

        Files.writeString(repo.resolve("src/test/java/com/example/BrokenCompileTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class BrokenCompileTest {
                    @Test
                    void syntaxError() {
                        this is not valid java
                    }
                }
                """);

        return new MavenProjectAdapter(repo.toString(),
                "Dependency/junit-platform-console-standalone-1.11.4.jar");
    }

    @Test
    void discoversJUnitTests() throws Exception {
        MavenProjectAdapter adapter = buildFixtureRepo();
        List<String> tests = adapter.discoverTests(null);
        assertEquals(3, tests.size(), "应发现3个测试类: " + tests);
        assertTrue(tests.contains("com/example/CalcPassTest.java"));
        assertTrue(tests.contains("com/example/CalcFailTest.java"));

        // 按子包路径发现
        List<String> scoped = adapter.discoverTests("com/example");
        assertEquals(3, scoped.size());

        // resolveTestFile 还原
        File f = adapter.resolveTestFile("com/example/CalcPassTest.java");
        assertTrue(f.exists());
    }

    @Test
    void executesPassingTest() throws Exception {
        MavenProjectAdapter adapter = buildFixtureRepo();
        TestCase tc = new TestCase(adapter.resolveTestFile("com/example/CalcPassTest.java"));
        TestResult result = adapter.executeTest(tc);
        assertEquals(TestResultKind.SUCCESS, result.getKind(),
                "通过用例应为SUCCESS: " + result);
    }

    @Test
    void executesFailingTest() throws Exception {
        MavenProjectAdapter adapter = buildFixtureRepo();
        TestCase tc = new TestCase(adapter.resolveTestFile("com/example/CalcFailTest.java"));
        TestResult result = adapter.executeTest(tc);
        assertEquals(TestResultKind.TEST_FAIL, result.getKind());
        assertFalse(result.getCompilationFailed());
        assertTrue(result.getOutput().contains("boom-expected-failure"),
                "输出应包含断言消息: " + result.getOutput());
    }

    @Test
    void compileFailureSetsFlag() throws Exception {
        MavenProjectAdapter adapter = buildFixtureRepo();
        TestCase tc = new TestCase(adapter.resolveTestFile("com/example/BrokenCompileTest.java"));
        TestResult result = adapter.executeTest(tc);
        assertTrue(result.isFail(), "编译错误应为失败类: " + result.getKind());
        assertTrue(result.getCompilationFailed(), "应标记compilationFailed");
    }

    @Test
    void fqnDerivation() throws Exception {
        Path src = tempDir.resolve("SomeTest.java");
        Files.writeString(src, "package a.b.c;\npublic class SomeTest {}\n");
        assertEquals("a.b.c.SomeTest", MavenProjectAdapter.deriveFullyQualifiedName(src));

        Path noPkg = tempDir.resolve("NoPkg.java");
        Files.writeString(noPkg, "public class NoPkg {}\n");
        assertEquals("NoPkg", MavenProjectAdapter.deriveFullyQualifiedName(noPkg));
    }
}