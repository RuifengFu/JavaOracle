package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter;
import edu.tju.ista.llm4test.adapter.maven.MavenProjectAdapter;
import edu.tju.ista.llm4test.config.GlobalConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器路径模型：套件根 / 工作区源根 / 用户路径拼接 / 原始文件→工作区副本。
 * <p>
 * 这四件事原先散落在 {@code CommandHandler}、{@code TestExecutionManager}、
 * {@code TestSuite} 里硬编码 JDK 路径前缀（issue #15 第 13 条）。本测试把 JDK 模式
 * 迁移前的取值逐字锁住，并覆盖 Maven 模式的对应取值。
 */
class AdapterPathModelTest {

    @TempDir
    Path tempDir;

    private static Path abs(String first, String... more) {
        return Path.of(first, more).toAbsolutePath().normalize();
    }

    // ==================== JDK 模式：锁住迁移前的取值 ====================

    @Test
    void jdkSuiteRootIsSuiteBasePath() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();

        assertEquals(GlobalConfig.getSuiteBasePath(), adapter.suiteRoot());
        // 历史等价形式：jdkTestPath + "/jdk/"
        assertEquals(GlobalConfig.getJdkTestPath() + "/jdk/", adapter.suiteRoot(),
                "suiteBasePath 与 jdkTestPath+/jdk/ 必须一致，否则缓存相对化与发现前缀会分叉");
    }

    @Test
    void jdkWorkspaceSourceRootIsOneLevelAboveSuiteRoot() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();

        assertEquals(GlobalConfig.getJdkTestPath(), adapter.workspaceSourceRoot(),
                "复制根是 jdk17u-dev/test（含 jdk/ 子目录），比套件根高一层");
    }

    @Test
    void jdkResolveSuitePathMatchesLegacyConcatenation() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();

        // 迁移前 CommandHandler 的写法就是字符串拼接
        String legacy = GlobalConfig.getSuiteBasePath() + "java/lang/Byte";
        assertEquals(Path.of(legacy).toString(), adapter.resolveSuitePath("java/lang/Byte"));

        assertEquals(Path.of(GlobalConfig.getSuiteBasePath() + "java").toString(),
                adapter.resolveSuitePath("java"));
        assertEquals(adapter.suiteRoot(), adapter.resolveSuitePath(null));
        assertEquals(adapter.suiteRoot(), adapter.resolveSuitePath(""));
        assertEquals("/abs/path/Foo.java", adapter.resolveSuitePath("/abs/path/Foo.java"),
                "绝对路径原样透传");
    }

    @Test
    void jdkToWorkspaceFileMatchesLegacyStringReplace() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();
        File origin = abs(GlobalConfig.getJdkTestPath(), "jdk/java/lang/Byte/Decode.java").toFile();

        File workspace = adapter.toWorkspaceFile(origin);

        // 迁移前：originAbsolutePath.replace(jdkTestPath, testDir)
        String legacy = origin.getAbsolutePath()
                .replace(GlobalConfig.getJdkTestPath(), GlobalConfig.getTestDir());
        assertEquals(new File(legacy).getAbsolutePath(), workspace.getAbsolutePath());
        assertTrue(workspace.getAbsolutePath().endsWith("/test/jdk/java/lang/Byte/Decode.java"),
                "工作区路径应保留 jdk/ 一层: " + workspace);
    }

    @Test
    void toWorkspaceFileLeavesOutsideFilesUntouched() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();
        File outside = tempDir.resolve("Elsewhere.java").toFile();

        assertEquals(outside, adapter.toWorkspaceFile(outside),
                "不在复制根之下的文件应原样返回，而不是拼出 ../.. 路径");
    }

    // ==================== Maven 模式 ====================

    @Test
    void mavenSuiteRootIsAbsoluteTestSourceRoot() {
        MavenProjectAdapter adapter = new MavenProjectAdapter(tempDir.toString(), "unused.jar");

        Path expected = tempDir.resolve("src/test/java").toAbsolutePath().normalize();
        assertEquals(expected.toString(), adapter.suiteRoot());
        assertEquals(adapter.suiteRoot(), adapter.workspaceSourceRoot(),
                "Maven 的套件根与复制根相同（src/test/java 即包根）");
    }

    @Test
    void mavenResolveSuitePathJoinsWithoutTrailingSlashBug() {
        MavenProjectAdapter adapter = new MavenProjectAdapter(tempDir.toString(), "unused.jar");

        String resolved = adapter.resolveSuitePath("org/apache/commons/lang3");

        // 直接字符串拼接会得到 .../src/test/javaorg/apache/... —— join 必须走 Path
        assertEquals(tempDir.resolve("src/test/java/org/apache/commons/lang3")
                .toAbsolutePath().normalize().toString(), resolved);
        assertFalse(resolved.contains("javaorg"), "不能出现缺失分隔符的拼接: " + resolved);
    }

    @Test
    void mavenToWorkspaceFileMapsIntoTestDir() {
        MavenProjectAdapter adapter = new MavenProjectAdapter(tempDir.toString(), "unused.jar");
        File origin = tempDir.resolve("src/test/java/org/apache/commons/lang3/CharUtilsTest.java")
                .toAbsolutePath().normalize().toFile();

        File workspace = adapter.toWorkspaceFile(origin);

        Path expected = Path.of(GlobalConfig.getTestDir()).toAbsolutePath().normalize()
                .resolve("org/apache/commons/lang3/CharUtilsTest.java");
        assertEquals(expected.toString(), workspace.getAbsolutePath(),
                "Maven 工作区副本不带 jdk/ 前缀，直接是包路径");
    }
}
