package edu.tju.ista.llm4test.adapter.maven;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.utils.JavaSourceUtils;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Maven 仓库的测试执行工具（JUnit console harness）。
 * <p>
 * 与 {@link JtregExecuteTool 的 jtreg 版本} 对位：接受同样的三参数
 * （{@code content} / {@code is_file_path} / {@code class_name}），
 * 源码字符串先落成临时文件再执行，因此 Agent 侧无需感知 harness 差异。
 * <p>
 * 执行本身复用 {@link MavenProjectAdapter#executeTest}，从而共享它的
 * workspace / classpath 缓存与输出分类，不重复一套 javac + console 调用。
 */
public class MavenExecuteTool implements TestExecuteTool {

    /** 源码字符串落盘的临时根目录（相对工作目录，便于清理与排查） */
    private static final String WORKSPACE_DIR = "junit-workspace";

    private final MavenProjectAdapter adapter;
    private final File baseWorkingDir;

    public MavenExecuteTool(MavenProjectAdapter adapter) {
        this.adapter = adapter;
        this.baseWorkingDir = new File(WORKSPACE_DIR);
        baseWorkingDir.mkdirs();
    }

    /** 工具名：LLM 按它发起调用，prompt 模板的 ${harness.executeToolName} 取自同一常量 */
    public static final String TOOL_NAME = "junit_execute";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Execute the specified Java JUnit test file or Java source code against the target Maven "
                + "repository (its compiled classes and dependencies are on the classpath), and return the "
                + "test execution results. Use the is_file_path parameter to explicitly specify whether "
                + "content is a file path or source code.";
    }

    @Override
    public ToolResponse<TestResult> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey(PARAM_CONTENT) || !args.containsKey(PARAM_IS_FILE_PATH)) {
            return ToolResponse.failure("参数错误，必须提供 content 和 is_file_path");
        }

        String content = (String) args.get(PARAM_CONTENT);
        boolean isFilePath = Boolean.TRUE.equals(args.get(PARAM_IS_FILE_PATH));
        String className = (String) args.get(PARAM_CLASS_NAME);

        try {
            File testFile = isFilePath
                    ? new File(content.trim())
                    : createTemporaryTestFile(content, className);

            if (!testFile.exists()) {
                return ToolResponse.failure("测试文件不存在: " + testFile.getAbsolutePath());
            }

            // 复用适配器的执行链路（workspace/classpath 缓存 + 输出分类）
            TestResult result = adapter.executeTest(new TestCase(testFile));
            return ToolResponse.success(result);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "MavenExecuteTool执行失败: " + e.getMessage());
            return ToolResponse.failure("MavenExecuteTool执行时发生异常: " + e.getMessage());
        }
    }

    /**
     * 把源码字符串写成临时测试文件。
     * <p>
     * 目录按包结构展开：{@code MavenProjectAdapter} 用 package 声明 + 文件名推导 FQN，
     * 而 javac 的 {@code -d} 输出也按包分层，保持一致可避免类名不匹配。
     */
    private File createTemporaryTestFile(String sourceCode, String className) throws IOException {
        String resolved = className != null ? className : JavaSourceUtils.extractMainClassName(sourceCode);
        String actualClassName = resolved != null
                ? resolved
                : "TestClass_" + UUID.randomUUID().toString().replace("-", "");

        File testCaseDir = new File(baseWorkingDir,
                actualClassName + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId());

        String packageName = JavaSourceUtils.extractPackageName(sourceCode);
        File testDir = packageName != null
                ? new File(testCaseDir, packageName.replace('.', File.separatorChar))
                : testCaseDir;
        Files.createDirectories(testDir.toPath());

        Path testFile = new File(testDir, actualClassName + ".java").toPath();
        Files.writeString(testFile, sourceCode, StandardCharsets.UTF_8);
        LoggerUtil.logExec(Level.INFO, "创建测试文件: " + testFile.toAbsolutePath());
        return testFile.toFile();
    }
}
