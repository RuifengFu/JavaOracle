package edu.tju.ista.llm4test.adapter.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Maven 测试发现的路径语义测试：纯文件遍历，不需要 mvn / junit-console，
 * 因此在任何环境都会运行。
 * <p>
 * 重点锁住相对 {@code project.root} 的场景：默认配置就是 {@code project.root=.}，
 * 而遍历得到的是绝对路径；如果测试根不绝对化，{@code Path.relativize} 会抛
 * IllegalArgumentException 并被 catch 吞掉，发现结果静默变成空列表。
 */
class MavenProjectAdapterDiscoveryTest {

    @TempDir
    Path tempDir;

    private Path buildRepo() throws Exception {
        Path repo = tempDir.resolve("demo-repo");
        Path testPkg = repo.resolve("src/test/java/com/example");
        Files.createDirectories(testPkg);
        Files.createDirectories(repo.resolve("src/main/java/com/example"));

        Files.writeString(testPkg.resolve("AlphaTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                class AlphaTest {
                    @Test
                    void a() {}
                }
                """);
        Files.writeString(testPkg.resolve("BetaTest.java"), """
                package com.example;
                import org.junit.jupiter.api.ParameterizedTest;
                class BetaTest {
                    @ParameterizedTest
                    void b() {}
                }
                """);
        // 无测试注解的辅助类不应被发现
        Files.writeString(testPkg.resolve("TestHelper.java"), """
                package com.example;
                class TestHelper {
                    static int helper() { return 1; }
                }
                """);
        // 主源码即使含 @Test 字样也不在测试根下，不应被发现
        Files.writeString(repo.resolve("src/main/java/com/example/Calc.java"), """
                package com.example;
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        return repo;
    }

    private static MavenProjectAdapter adapterFor(String projectRoot) {
        return new MavenProjectAdapter(projectRoot, "Dependency/junit-platform-console-standalone-1.11.4.jar");
    }

    @Test
    void discoversTestsWithAbsoluteProjectRoot() throws Exception {
        Path repo = buildRepo();
        List<String> found = adapterFor(repo.toString()).discoverTests(".");
        assertEquals(List.of("com/example/AlphaTest.java", "com/example/BetaTest.java"), found);
    }

    @Test
    void discoversTestsWithRelativeProjectRoot() throws Exception {
        Path repo = buildRepo();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String relativeRoot = cwd.relativize(repo).toString();
        assertFalse(Path.of(relativeRoot).isAbsolute(), "前置条件：project.root 必须是相对路径");

        List<String> found = adapterFor(relativeRoot).discoverTests(".");
        assertEquals(List.of("com/example/AlphaTest.java", "com/example/BetaTest.java"), found,
                "相对 project.root 下发现结果应与绝对路径一致");
    }

    @Test
    void scanSubPathIsResolvedAgainstTestRoot() throws Exception {
        Path repo = buildRepo();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String relativeRoot = cwd.relativize(repo).toString();

        List<String> found = adapterFor(relativeRoot).discoverTests("com/example");
        assertEquals(List.of("com/example/AlphaTest.java", "com/example/BetaTest.java"), found);
    }

    @Test
    void missingTestRootReturnsEmptyList() {
        List<String> found = adapterFor(tempDir.resolve("no-such-repo").toString()).discoverTests(".");
        assertTrue(found.isEmpty());
    }

    @Test
    void resolveTestFilePointsIntoTestRoot() throws Exception {
        Path repo = buildRepo();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String relativeRoot = cwd.relativize(repo).toString();

        Path resolved = adapterFor(relativeRoot).resolveTestFile("com/example/AlphaTest.java").toPath();
        assertTrue(resolved.isAbsolute(), "解析结果应为绝对路径");
        assertEquals(repo.resolve("src/test/java/com/example/AlphaTest.java").toRealPath(),
                resolved.toRealPath());
    }
}
