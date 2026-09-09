package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 目标项目适配器（SPI）：封装与"被测项目形态"相关的全部细节——
 * 测试发现、测试执行、环境检查。
 * <p>
 * 现有实现：
 * <ul>
 *   <li>{@link edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter} —— JDK/jtreg 场景（永久保留）</li>
 *   <li>（规划）MavenProjectAdapter —— 第三方 Maven 仓库的 JUnit 测试增强</li>
 * </ul>
 * <p>
 * 约定：适配器只负责"执行并分类结果"（SUCCESS/COMPILE_FAIL/TEST_FAIL/EXECUTE_TIMEOUT等），
 * "真bug vs 测试问题"的裁决（VERIFIED_BUG）统一由上层 LLM 流程完成。
 */
public interface ProjectAdapter {

    /** 适配器标识，如 "jdk"、"maven" */
    String id();

    // ==================== 测试发现 ====================

    /**
     * 发现测试用例。
     * @param rootPath 用户输入的目标路径（目录或单个文件，语义同 Main execute/generate 参数）
     * @return 套件相对路径标识列表（后续可用 {@link #resolveTestFile} 还原为文件）
     */
    List<String> discoverTests(String rootPath);

    /**
     * 将 discoverTests 返回的相对路径标识还原为实际测试文件
     */
    File resolveTestFile(String relativeTestPath);

    /**
     * 测试文件有效性检查（默认按全局大小限制，与历史行为一致）
     */
    default boolean isValidTest(File test) {
        try {
            return test.exists() && test.length() <= GlobalConfig.getMaxFileSize();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 测试执行 ====================

    /**
     * 执行单个测试用例并返回分类结果。
     * JDK实现为多JDK差分执行；其他实现为单环境执行。
     */
    TestResult executeTest(TestCase testCase);

    // ==================== harness 说明（注入prompt模板） ====================

    /**
     * harness 指令片段：由 {@code PromptGen} 自动注入模板的 {@code ${harness.*}} 变量，
     * 使增强/修复/最小化等提示词与具体测试框架解耦。
     * <p>
     * 约定键：
     * <ul>
     *   <li>{@code name} —— 框架名（如 jtreg / JUnit 5）</li>
     *   <li>{@code tagList} —— 必须保留的标记列表文案</li>
     *   <li>{@code tagExample} —— 标记示例代码块</li>
     *   <li>{@code executeToolName} —— 执行工具名（LLM工具调用）</li>
     * </ul>
     */
    default Map<String, String> harnessDirectives() {
        // 默认按 JUnit 5 约定
        Map<String, String> directives = new java.util.HashMap<>();
        directives.put("name", "JUnit 5");
        directives.put("tagList", "(`@Test`, `@BeforeEach`, `@DisplayName`, ...)");
        directives.put("tagExample", """
                ```
                @Test
                void enhancedBehavior() {
                    // assertions
                }
                ```""");
        directives.put("executeToolName", "execute_test");
        return directives;
    }

    // ==================== 环境 ====================

    /** 轻量环境检查（执行器初始化时调用，仅打印告警不抛异常） */
    void checkEnvironment();

    /** 环境自检（env 命令入口：实际跑一遍目标运行时） */
    void verifyEnvironment();
}