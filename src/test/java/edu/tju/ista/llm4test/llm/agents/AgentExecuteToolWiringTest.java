package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.adapter.jdk.JtregExecuteTool;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import edu.tju.ista.llm4test.llm.tools.ToolRegistry;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智能体执行工具接线的安全网（issue #15 步骤 1）。
 * <p>
 * 锁住 JDK 模式下三个 Agent 与执行工具之间的现有契约，以便后续把
 * {@code new JtregExecuteTool()} 迁移到 {@code AdapterRegistry.get().createExecuteTool()}
 * 时能证明行为等价。被锁住的四件事：
 * <ol>
 *   <li>工具**名字**：LLM 按名字发起调用，且 TestCaseAgent 里存在同名字面量</li>
 *   <li>工具**schema**：LLM 可见的参数列表/类型/描述</li>
 *   <li>**便捷重载**到 {@code execute(Map)} 的参数映射（HypothesisAgent / BugVerify 依赖）</li>
 *   <li>write_to_file 之后**自动追加执行调用**的名字、参数与顺序（TestCaseAgent 依赖）</li>
 * </ol>
 * 全部走反射，不改生产代码；不触发真实 jtreg 执行。
 */
class AgentExecuteToolWiringTest {

    /** 迁移后此名字在 JDK 模式必须保持不变（prompt 模板与 TestCaseAgent 均按它匹配） */
    private static final String EXECUTE_TOOL_NAME = "jtreg_execute";

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    // ==================== 1. 工具名字与注册表内容 ====================

    @Test
    void testCaseAgentRegistersWriteAndExecuteTools() throws Exception {
        ToolRegistry registry = field(new TestCaseAgent(), "toolRegistry");

        assertEquals(Set.of("write_to_file", EXECUTE_TOOL_NAME), registry.getToolNames(),
                "TestCaseAgent 注册的工具集合变化会直接改变 LLM 可见的能力");
        assertTrue(registry.has(EXECUTE_TOOL_NAME));
        assertEquals(EXECUTE_TOOL_NAME, registry.get(EXECUTE_TOOL_NAME).getName());
    }

    @Test
    void hypothesisAgentHoldsExecuteToolUnderExpectedName() throws Exception {
        Tool<TestResult> tool = field(new HypothesisAgent(), "jtregTool");

        assertNotNull(tool, "HypothesisAgent 必须持有执行工具");
        assertEquals(EXECUTE_TOOL_NAME, tool.getName());
    }

    @Test
    void bugVerifyHoldsExecuteToolUnderExpectedName() throws Exception {
        // 传 null 源码/文档路径：仅构造，不做索引与网络
        Tool<TestResult> tool = field(new BugVerify(null, null), "jtregTool");

        assertNotNull(tool, "BugVerify 必须持有执行工具");
        assertEquals(EXECUTE_TOOL_NAME, tool.getName());
    }

    // ==================== 2. LLM 可见的 schema ====================

    @Test
    void executeToolSchemaIsStable() {
        JtregExecuteTool tool = new JtregExecuteTool();

        assertEquals(EXECUTE_TOOL_NAME, tool.getName());
        assertEquals(List.of("content", "is_file_path", "class_name"), tool.getParameters(),
                "参数名与顺序构成 LLM 调用契约");
        assertEquals(Map.of(
                "content", "string",
                "is_file_path", "boolean",
                "class_name", "string"), tool.getParametersType());
        assertEquals(Set.of("content", "is_file_path", "class_name"),
                tool.getParametersDescription().keySet());
        assertTrue(tool.getDescription().contains("jtreg"),
                "JDK 模式的工具描述应说明使用 jtreg，实际: " + tool.getDescription());
        // toMap 会校验三张表长度一致，顺带锁住 schema 自洽
        assertDoesNotThrow(tool::toMap);
    }

    // ==================== 3. 便捷重载的参数映射 ====================

    /** 记录 execute(Map) 收到的参数，不真正执行 jtreg */
    private static class RecordingExecuteTool extends JtregExecuteTool {
        Map<String, Object> lastArgs;

        @Override
        public ToolResponse<TestResult> execute(Map<String, Object> args) {
            this.lastArgs = new LinkedHashMap<>(args);
            return ToolResponse.success(new TestResult(TestResultKind.SUCCESS));
        }
    }

    @Test
    void sourceCodeOverloadMapsToNonFilePathArgs() {
        RecordingExecuteTool tool = new RecordingExecuteTool();
        String code = """
                public class HypothesisProbe {
                    public static void main(String[] args) {}
                }
                """;

        // HypothesisAgent:237 走的这条重载
        tool.execute(code);

        assertEquals(code, tool.lastArgs.get("content"));
        assertEquals(false, tool.lastArgs.get("is_file_path"));
        assertEquals("HypothesisProbe", tool.lastArgs.get("class_name"),
                "class_name 由源码推导（extractClassNameFromSource）");
    }

    @Test
    void filePathOverloadMapsToFilePathArgs() {
        RecordingExecuteTool tool = new RecordingExecuteTool();
        Path file = tempDir.resolve("ByteProbe.java");

        // BugVerify:230,2199,2272 走的这条重载
        tool.execute(file, "ByteProbe");

        assertEquals(file.toString(), tool.lastArgs.get("content"));
        assertEquals(true, tool.lastArgs.get("is_file_path"));
        assertEquals("ByteProbe", tool.lastArgs.get("class_name"));
    }

    // ==================== 4. write_to_file 之后自动追加执行调用 ====================

    /** 记录调用参数的桩工具，用于拦截 act() 而不触发真实执行 */
    private static class StubTool implements Tool<TestResult> {
        private final String name;
        Map<String, Object> lastArgs;

        StubTool(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "stub"; }
        @Override public List<String> getParameters() { return List.of("content"); }
        @Override public Map<String, String> getParametersDescription() { return Map.of("content", "stub"); }
        @Override public Map<String, String> getParametersType() { return Map.of("content", "string"); }

        @Override
        public ToolResponse<TestResult> execute(Map<String, Object> args) {
            this.lastArgs = new LinkedHashMap<>(args);
            return ToolResponse.success(new TestResult(TestResultKind.SUCCESS));
        }
    }

    @Test
    void actAutoAppendsExecuteCallAfterWriteToFile() throws Exception {
        TestCaseAgent agent = new TestCaseAgent();

        Path testFile = tempDir.resolve("ByteProbe.java");
        Files.writeString(testFile, "public class ByteProbe {}\n");
        TestCase testCase = new TestCase(testFile.toFile());

        Field tcField = TestCaseAgent.class.getDeclaredField("testCase");
        tcField.setAccessible(true);
        tcField.set(agent, testCase);

        // 用桩替换两个真实工具（ToolRegistry 按名字覆盖注册）
        ToolRegistry registry = field(agent, "toolRegistry");
        StubTool writeStub = new StubTool("write_to_file");
        StubTool executeStub = new StubTool(EXECUTE_TOOL_NAME);
        registry.register(writeStub);
        registry.register(executeStub);

        Map<String, Object> writeArgs = new HashMap<>();
        writeArgs.put("path", "whatever-the-llm-said.java");
        writeArgs.put("content", "public class ByteProbe { int x; }\n");
        List<ToolCall> calls = new ArrayList<>();
        calls.add(new ToolCall("write_to_file", writeArgs));

        Method act = TestCaseAgent.class.getDeclaredMethod("act", List.class);
        act.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolResponse<?>> responses = (List<ToolResponse<?>>) act.invoke(agent, calls);

        List<String> executed = field(agent, "lastExecutedTools");
        assertEquals(List.of("write_to_file", EXECUTE_TOOL_NAME), executed,
                "写文件之后必须自动执行一次测试，且顺序固定（observe 按下标取结果）");
        assertEquals(2, responses.size());

        assertNotNull(executeStub.lastArgs, "执行工具应被调用");
        File expectedFile = testCase.getFile();
        assertEquals(expectedFile.getAbsolutePath(), executeStub.lastArgs.get("content"),
                "自动执行的目标固定为用例文件的绝对路径");
        assertEquals(true, executeStub.lastArgs.get("is_file_path"));
        assertEquals(testCase.getName(), executeStub.lastArgs.get("class_name"));

        assertEquals(expectedFile.getAbsolutePath(), writeStub.lastArgs.get("path"),
                "write_to_file 的 path 被强制改写为用例文件，忽略 LLM 给的路径");
    }
}
