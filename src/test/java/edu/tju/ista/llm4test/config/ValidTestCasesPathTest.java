package edu.tju.ista.llm4test.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过列表（pass list）文件名的生成规则。
 * <p>
 * 关键性质：**不能把机器绝对路径写进文件名**。CI 曾把 {@code $GITHUB_WORKSPACE}
 * 拼进 suiteBasePath，于是仓库里留下了
 * {@code _home_runner_work_JavaOracle_JavaOracle_jdk17u-dev_test_jdk_java.txt}
 * 这种文件名——换 runner 或换检出目录后缓存直接失效。现在统一先相对化到工作目录。
 */
class ValidTestCasesPathTest {

    private static String fileNameFor(String rootPath) {
        return Path.of(GlobalConfig.getValidTestCasesPath(rootPath)).getFileName().toString();
    }

    @Test
    void relativeSuitePathKeepsItsShape() {
        assertEquals("jdk17u-dev_test_jdk_java_lang_Byte.txt",
                fileNameFor("jdk17u-dev/test/jdk/java/lang/Byte"));
        assertEquals("jdk17u-dev_test_jdk_java.txt", fileNameFor("jdk17u-dev/test/jdk/java"));
    }

    @Test
    void absoluteSuitePathUnderWorkdirIsRelativized() {
        // CI 的写法：$GITHUB_WORKSPACE/jdk17u-dev/test/jdk/java
        String absolute = Path.of("").toAbsolutePath().normalize()
                .resolve("jdk17u-dev/test/jdk/java").toString();

        assertEquals("jdk17u-dev_test_jdk_java.txt", fileNameFor(absolute),
                "绝对配置也必须落到与相对配置相同的文件名");
    }

    @Test
    void repositoryOutsideWorkdirDropsParentSegments() {
        // Maven 模式的同级检出：../commons-lang/src/test/java/...
        String sibling = Path.of("").toAbsolutePath().normalize()
                .resolve("../commons-lang/src/test/java/org/apache/commons/lang3/math")
                .normalize().toString();

        String name = fileNameFor(sibling);

        assertEquals("commons-lang_src_test_java_org_apache_commons_lang3_math.txt", name);
        assertFalse(name.contains(".."), "不应出现 .. 片段: " + name);
        assertFalse(name.startsWith("_"), "不应以分隔符开头（绝对路径的痕迹）: " + name);
    }

    @Test
    void blankRootPathFallsBackToDefault() {
        assertEquals("default.txt", fileNameFor(""));
        assertEquals("default.txt", fileNameFor(null));
    }

    @Test
    void maxFileSizeGuardrailIsHalfMegabyte() {
        assertEquals(512L * 1024, GlobalConfig.getMaxFileSize(),
                "护栏值应为 512KiB：旧的 10000 字节会静默吃掉第三方库的大半测试文件");
    }
}
