package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiInfoProcessor 构造期配置健壮性测试（不依赖任何本地源码/文档树）。
 * <p>
 * baseDocPath 是可选配置（config.properties 允许留空），而 TestExecutionManager
 * 在所有命令下都会构造 ApiInfoProcessor —— 构造函数必须能吞下空值与单段路径，
 * 否则应用直接起不来。
 */
class ApiInfoProcessorConfigTest {

    @Test
    void blankBaseDocPathDoesNotThrow() {
        assertDoesNotThrow(() -> new ApiInfoProcessor(""));
        assertDoesNotThrow(() -> new ApiInfoProcessor("   "));
        assertDoesNotThrow(() -> new ApiInfoProcessor("", "jdk17u-dev/src", "java.base/share/classes"));
    }

    @Test
    void singleSegmentBaseDocPathDoesNotThrow() {
        // Path.of("JavaDoc").getParent() == null —— 单段路径同样没有父目录
        assertDoesNotThrow(() -> new ApiInfoProcessor("JavaDoc"));
        assertDoesNotThrow(() -> new ApiInfoProcessor("JavaDoc", "jdk17u-dev/src", "java.base/share/classes"));
    }

    @Test
    void nullBaseDocPathDoesNotThrow() {
        assertDoesNotThrow(() -> new ApiInfoProcessor(null));
        assertDoesNotThrow(() -> new ApiInfoProcessor(null, null, null));
    }

    @Test
    void nestedBaseDocPathStillConstructs() {
        assertDoesNotThrow(() -> new ApiInfoProcessor("JavaDoc/docs/api/java.base",
                "jdk17u-dev/src", "java.base/share/classes"));
    }
}
