package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.JavaSourceUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 测试执行工具（LLM 工具）：由 {@code ProjectAdapter.createExecuteTool()} 提供，
 * 各 harness 一个实现（jtreg / JUnit console / …）。
 * <p>
 * 之所以不能只用 {@code Tool<TestResult>}：三个 Agent 用的是 {@code Tool} 接口上
 * 没有的便捷重载——{@code HypothesisAgent} 传源码字符串、{@code BugVerify} 传文件路径。
 * 这里把它们收成 default 方法，统一转成 {@code execute(Map)} 的三参数约定，
 * 于是各实现只需实现 {@code execute(Map)}，Agent 侧零改动。
 * <p>
 * 三参数约定（工具 schema 的一部分，改动会影响 LLM 调用）：
 * {@code content} / {@code is_file_path} / {@code class_name}。
 */
public interface TestExecuteTool extends Tool<TestResult> {

    String PARAM_CONTENT = "content";
    String PARAM_IS_FILE_PATH = "is_file_path";
    String PARAM_CLASS_NAME = "class_name";

    @Override
    default List<String> getParameters() {
        return List.of(PARAM_CONTENT, PARAM_IS_FILE_PATH, PARAM_CLASS_NAME);
    }

    @Override
    default Map<String, String> getParametersType() {
        return Map.of(
                PARAM_CONTENT, "string",
                PARAM_IS_FILE_PATH, "boolean",
                PARAM_CLASS_NAME, "string"
        );
    }

    @Override
    default Map<String, String> getParametersDescription() {
        return Map.of(
                PARAM_CONTENT, "Java source code or path to the test file.",
                PARAM_IS_FILE_PATH, "True if 'content' is a file path, false if it is source code.",
                PARAM_CLASS_NAME, "(Optional) When providing source code, specify the main class name."
        );
    }

    /**
     * 便捷重载：直接执行一段源码（HypothesisAgent 用）。
     * 类名从源码推导，实现侧负责落成临时文件。
     */
    default ToolResponse<TestResult> execute(String sourceCode) {
        return execute(args(sourceCode, false, extractClassNameFromSource(sourceCode)));
    }

    /** 便捷重载：执行已落盘的测试文件（BugVerify 用） */
    default ToolResponse<TestResult> execute(Path filePath, String className) {
        return execute(args(filePath.toString(), true, className));
    }

    /**
     * 组装三参数。用 HashMap 而不是 {@code Map.of}：类名推导失败时为 null，
     * 而 {@code Map.of} 遇到 null 值会抛 NPE（历史实现在这里是会炸的），
     * {@code execute(Map)} 本身允许 class_name 缺省。
     */
    private static Map<String, Object> args(String content, boolean isFilePath, String className) {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put(PARAM_CONTENT, content);
        args.put(PARAM_IS_FILE_PATH, isFilePath);
        args.put(PARAM_CLASS_NAME, className);
        return args;
    }

    /** 从源码推导主类名（与 harness 无关，实现见 {@link JavaSourceUtils}） */
    default String extractClassNameFromSource(String sourceCode) {
        return JavaSourceUtils.extractMainClassName(sourceCode);
    }
}
