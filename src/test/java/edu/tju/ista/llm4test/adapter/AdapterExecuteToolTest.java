package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter;
import edu.tju.ista.llm4test.adapter.jdk.JtregExecuteTool;
import edu.tju.ista.llm4test.adapter.maven.MavenExecuteTool;
import edu.tju.ista.llm4test.adapter.maven.MavenProjectAdapter;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 适配器提供的执行工具（{@code ProjectAdapter.createExecuteTool()}）与 harness 指令的一致性。
 * <p>
 * 核心是一条防漂移断言：{@code createExecuteTool().getName()} 必须等于
 * {@code harnessDirectives().get("executeToolName")}。二者是两条独立的路径——
 * 前者决定 ToolRegistry 里**实际注册**的工具名，后者被注入 prompt 模板、告诉 LLM
 * **该调哪个名字**。一旦分叉，LLM 会去调一个未注册的工具，每次调用都静默失败
 * （既不报错也不执行测试），而单看任何一侧代码都完全正常。
 * <p>
 * 另外锁住：两个 harness 的工具 schema 完全相同（Agent 无论跑 jtreg 还是 JUnit
 * 都传同一组三参数），以及 Maven 适配器覆盖 executeToolName 时没有丢掉其他指令键。
 */
class AdapterExecuteToolTest {

    /** harness 指令里执行工具名的键（PromptGen 注入模板的 ${harness.executeToolName}） */
    private static final String EXECUTE_TOOL_NAME_KEY = "executeToolName";

    /** 三参数约定：参数名与顺序构成 LLM 调用契约 */
    private static final List<String> EXPECTED_PARAMS = List.of("content", "is_file_path", "class_name");

    @TempDir
    Path tempDir;

    private MavenProjectAdapter mavenAdapter() {
        // 不触发真实构建：只需要一个合法的 projectRoot，junitConsoleJar 在本测试里用不到
        return new MavenProjectAdapter(tempDir.toString(), "unused.jar");
    }

    // ==================== 1. 防漂移：注册的工具名 == prompt 里告诉 LLM 的工具名 ====================

    @Test
    void jdkExecuteToolNameMatchesHarnessDirective() {
        JdkProjectAdapter adapter = new JdkProjectAdapter();

        String registeredName = adapter.createExecuteTool().getName();
        String promptedName = adapter.harnessDirectives().get(EXECUTE_TOOL_NAME_KEY);

        assertEquals(registeredName, promptedName,
                "JDK 模式：实际注册的工具名(" + registeredName + ") 与 prompt 告知 LLM 的工具名("
                        + promptedName + ") 不一致，LLM 的每次工具调用都会静默失败");
    }

    @Test
    void mavenExecuteToolNameMatchesHarnessDirective() {
        MavenProjectAdapter adapter = mavenAdapter();

        String registeredName = adapter.createExecuteTool().getName();
        String promptedName = adapter.harnessDirectives().get(EXECUTE_TOOL_NAME_KEY);

        assertEquals(registeredName, promptedName,
                "Maven 模式：实际注册的工具名(" + registeredName + ") 与 prompt 告知 LLM 的工具名("
                        + promptedName + ") 不一致，LLM 的每次工具调用都会静默失败");
    }

    // ==================== 2. 具体类型与名字 ====================

    @Test
    void jdkAdapterProvidesJtregExecuteTool() {
        TestExecuteTool tool = new JdkProjectAdapter().createExecuteTool();

        assertInstanceOf(JtregExecuteTool.class, tool, "JDK 模式必须用 jtreg harness");
        assertEquals("jtreg_execute", tool.getName(),
                "工具名是 LLM 调用契约的一部分，改名会让历史 prompt/日志失配");
        assertEquals(JtregExecuteTool.TOOL_NAME, tool.getName());
    }

    @Test
    void mavenAdapterProvidesMavenExecuteTool() {
        TestExecuteTool tool = mavenAdapter().createExecuteTool();

        assertInstanceOf(MavenExecuteTool.class, tool, "Maven 模式必须用 JUnit console harness");
        assertEquals("junit_execute", tool.getName());
        assertEquals(MavenExecuteTool.TOOL_NAME, tool.getName());
    }

    // ==================== 3. 两个 harness 的 schema 必须一模一样 ====================

    @Test
    void bothExecuteToolsExposeIdenticalThreeParamSchema() {
        TestExecuteTool jtreg = new JdkProjectAdapter().createExecuteTool();
        TestExecuteTool junit = mavenAdapter().createExecuteTool();

        // 参数名与顺序：Agent 侧无论哪个 harness 都传同样的三参数
        assertEquals(EXPECTED_PARAMS, jtreg.getParameters(), "jtreg 工具的参数列表/顺序");
        assertEquals(EXPECTED_PARAMS, junit.getParameters(), "junit 工具的参数列表/顺序");
        assertEquals(jtreg.getParameters(), junit.getParameters(),
                "两个 harness 的参数列表与顺序必须完全一致（default 方法统一提供）");

        Map<String, String> expectedTypes = Map.of(
                "content", "string",
                "is_file_path", "boolean",
                "class_name", "string");
        assertEquals(expectedTypes, jtreg.getParametersType());
        assertEquals(expectedTypes, junit.getParametersType());

        assertEquals(Set.copyOf(EXPECTED_PARAMS), jtreg.getParametersDescription().keySet());
        assertEquals(jtreg.getParametersDescription().keySet(), junit.getParametersDescription().keySet(),
                "参数描述的键集合必须一致");

        // toMap 会校验参数列表/类型/描述三张表长度一致，顺带锁住 schema 自洽
        assertDoesNotThrow(jtreg::toMap, "jtreg 工具 schema 自洽");
        assertDoesNotThrow(junit::toMap, "junit 工具 schema 自洽");
    }

    @Test
    void executeToolDescriptionsMentionTheirHarness() {
        TestExecuteTool jtreg = new JdkProjectAdapter().createExecuteTool();
        TestExecuteTool junit = mavenAdapter().createExecuteTool();

        assertTrue(jtreg.getDescription().contains("jtreg"),
                "JDK 工具描述应说明使用 jtreg，实际: " + jtreg.getDescription());
        assertTrue(junit.getDescription().contains("JUnit"),
                "Maven 工具描述应说明执行 JUnit 测试，实际: " + junit.getDescription());
    }

    // ==================== 4. 覆盖 executeToolName 不能丢掉其他 harness 指令 ====================

    @Test
    void mavenHarnessDirectivesKeepJUnit5Wording() {
        Map<String, String> directives = mavenAdapter().harnessDirectives();

        assertTrue(directives.get("name").contains("JUnit"),
                "Maven 适配器只覆盖了 executeToolName，框架名应仍是 JUnit 5，实际: " + directives.get("name"));
        assertEquals(MavenExecuteTool.TOOL_NAME, directives.get(EXECUTE_TOOL_NAME_KEY));
    }

    @Test
    void bothAdaptersProvideAllHarnessDirectiveKeys() {
        Set<String> requiredKeys = Set.of("name", "tagList", "tagExample", EXECUTE_TOOL_NAME_KEY);

        Map<String, String> jdk = new JdkProjectAdapter().harnessDirectives();
        Map<String, String> maven = mavenAdapter().harnessDirectives();

        assertTrue(jdk.keySet().containsAll(requiredKeys),
                "JDK harness 指令缺键会让模板里的 ${harness.*} 变量替换不掉，实际键: " + jdk.keySet());
        assertTrue(maven.keySet().containsAll(requiredKeys),
                "Maven harness 指令缺键（覆盖 executeToolName 时丢了默认值？），实际键: " + maven.keySet());

        for (String key : requiredKeys) {
            assertNotNull(jdk.get(key), "JDK harness 指令 " + key + " 不能为 null");
            assertNotNull(maven.get(key), "Maven harness 指令 " + key + " 不能为 null");
            assertFalse(jdk.get(key).isBlank(), "JDK harness 指令 " + key + " 不能为空");
            assertFalse(maven.get(key).isBlank(), "Maven harness 指令 " + key + " 不能为空");
        }

        assertEquals("jtreg", jdk.get("name"), "JDK 模式的框架名");
    }

    // ==================== 5. 便捷重载的参数映射（default 方法，两个 harness 共用） ====================

    /**
     * 记录 execute(Map) 收到的参数，不真正跑 JUnit console。
     * <p>
     * 便捷重载定义在 {@code TestExecuteTool} 接口上，因此这里在 Maven 工具上验证的
     * 映射行为，对 jtreg 工具同样成立（见 {@code AgentExecuteToolWiringTest} 的 JDK 侧）。
     */
    private static class RecordingMavenExecuteTool extends MavenExecuteTool {
        Map<String, Object> lastArgs;

        RecordingMavenExecuteTool(MavenProjectAdapter adapter) {
            super(adapter);
        }

        @Override
        public ToolResponse<TestResult> execute(Map<String, Object> args) {
            // 不用 Map.copyOf：class_name 允许为 null
            this.lastArgs = new HashMap<>(args);
            return ToolResponse.success(new TestResult(TestResultKind.SUCCESS));
        }
    }

    @Test
    void sourceCodeOverloadMapsToNonFilePathArgs() {
        RecordingMavenExecuteTool tool = new RecordingMavenExecuteTool(mavenAdapter());
        String code = """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class EnhancedProbeTest {
                    @Test
                    void probe() {}
                }
                """;

        tool.execute(code);

        assertEquals(code, tool.lastArgs.get("content"));
        assertEquals(false, tool.lastArgs.get("is_file_path"),
                "传源码时 is_file_path 必须为 false，否则会被当成路径去找文件");
        assertEquals("EnhancedProbeTest", tool.lastArgs.get("class_name"),
                "class_name 由源码推导（extractClassNameFromSource）");
    }

    @Test
    void filePathOverloadMapsToFilePathArgs() {
        RecordingMavenExecuteTool tool = new RecordingMavenExecuteTool(mavenAdapter());
        Path file = tempDir.resolve("FooTest.java");

        tool.execute(file, "Foo");

        assertEquals(file.toString(), tool.lastArgs.get("content"));
        assertEquals(true, tool.lastArgs.get("is_file_path"));
        assertEquals("Foo", tool.lastArgs.get("class_name"));
    }

    @Test
    void sourceCodeOverloadToleratesUnextractableClassName() {
        RecordingMavenExecuteTool tool = new RecordingMavenExecuteTool(mavenAdapter());
        // LLM 偶尔只回一个片段：推导不出类名。历史实现用 Map.of 组装三参数，
        // 遇到 null 值直接抛 NPE，把"编译失败"这种可恢复情况变成整轮崩溃。
        String fragment = "// 只有注释，没有类声明\nint x = 1;\n";

        assertDoesNotThrow(() -> tool.execute(fragment),
                "推导不出类名时不能抛异常（三参数用 HashMap 组装，容忍 null）");

        assertEquals(fragment, tool.lastArgs.get("content"));
        assertEquals(false, tool.lastArgs.get("is_file_path"));
        assertTrue(tool.lastArgs.containsKey("class_name"), "class_name 键仍应存在（值为 null）");
        assertNull(tool.lastArgs.get("class_name"), "推导不出类名时 class_name 为 null，由实现侧兜底命名");
    }
}
