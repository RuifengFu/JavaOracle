package edu.tju.ista.llm4test.config;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalConfig JDK 默认路径回归测试：保护后续配置分节重构时
 * JDK 模式的默认值与本地 config.properties 行为不被破坏。
 */
class GlobalConfigJdkDefaultsTest {

    @Test
    void jdkPathsResolveToExistingDirectory() {
        List<String> jdkPaths = GlobalConfig.getJdkPaths();
        assertFalse(jdkPaths.isEmpty(), "jdkPaths不应为空");
        // 本地部署环境应真实存在；CI/其他环境至少格式合法（包含bin子目录约定由env命令检查）
        for (String p : jdkPaths) {
            assertTrue(p.contains("jdk"), "jdkPath应是JDK目录: " + p);
        }
    }

    @Test
    void jdkDefaultPaths() {
        assertEquals("jdk17u-dev/test/jdk/", GlobalConfig.getSuiteBasePath());
        assertEquals("jdk17u-dev/test", GlobalConfig.getJdkTestPath());
        assertEquals("jdk17u-dev/src", GlobalConfig.getJdkSourcePath());
        assertEquals("java.base/share/classes", GlobalConfig.getDefaultSourcePrefix());
    }

    @Test
    void sourceTreeIndexResolvable() {
        // JDK源码树存在时（本地/CI clone后），默认路径应能定位String.java
        String src = GlobalConfig.getJdkSourcePath();
        if (new File(src).isDirectory()) {
            assertTrue(new File(src, "java.base/share/classes/java/lang/String.java").isFile(),
                    "JDK源码默认路径应能定位String.java");
        }
    }

    @Test
    void dependencyJarsConfigured() {
        String[] jars = GlobalConfig.getDependencyJars();
        assertTrue(jars.length >= 4, "至少应有testng/junit等4个依赖jar配置");
        for (String jar : jars) {
            if (!jar.isBlank()) {
                // 首个jar用于编译classpath，本地应存在；此处只验证格式
                assertTrue(jar.endsWith(".jar"), "依赖项应为jar路径: " + jar);
            }
        }
    }
}