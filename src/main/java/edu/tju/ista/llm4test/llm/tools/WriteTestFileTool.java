package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.JavaSourceUtils;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 写测试文件（供 LLM 以 tool call 方式产出用例）。
 * <p>
 * 只有一个参数 {@code content}：文件名与包名都从源码本身推导，而不是让模型另外给。
 * 这么设计是为了从结构上消掉三类反复出现的故障：
 * <ul>
 *   <li><b>拿不到代码</b>：旧做法是让模型「把完整用例放在最后一个 java 代码块里」，
 *       再用 markdown 解析抠出来。模型返回空正文（推理吃光额度）或返回一段解释性
 *       文字时，抠出来的是空串/散文，并被写进 .java —— 用例直接毁掉。
 *       tool call 要么带着结构化参数到达，要么不到达，没有中间态。</li>
 *   <li><b>文件名与 public 类不一致</b>：javac 要求同名，不然报
 *       "class X is public, should be declared in a file named X.java"；
 *       而 FQN 又是按 package + 文件名推导的。名字由源码决定就不可能不一致。</li>
 *   <li><b>写到用例目录之外</b>：没有 path 参数，模型无法指定路径，也就无从越界
 *       （{@link WriteFileTool} 接受任意路径，模型可以写到仓库任何地方）。</li>
 * </ul>
 * 同一次回复里调多次即多文件用例：辅助类/夹具各一个文件，带 {@code @Test} 的都会被执行。
 */
public class WriteTestFileTool implements Tool<String> {

    public static final String TOOL_NAME = "write_test_file";
    public static final String PARAM_CONTENT = "content";

    /** 写入的基准目录（用例所在包目录），模型无法越出它 */
    private final Path baseDir;

    /** 本次调用写出的文件，按写入顺序 */
    private final List<File> writtenFiles = new ArrayList<>();

    public WriteTestFileTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Write one complete Java source file of the test case. The file name and package are "
                + "derived from the source you provide, so the top-level type must be declared exactly "
                + "as you want it saved. Call this tool once per file: call it several times to produce a "
                + "multi-file test case (for example a test class plus a helper/fixture class). "
                + "Every class that declares @Test methods will be executed.";
    }

    @Override
    public List<String> getParameters() {
        return List.of(PARAM_CONTENT);
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(PARAM_CONTENT,
                "The complete Java source of one file, including its package declaration and imports.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(PARAM_CONTENT, "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey(PARAM_CONTENT)) {
            return ToolResponse.failure("参数错误，必须提供 " + PARAM_CONTENT);
        }
        Object raw = args.get(PARAM_CONTENT);
        String content = raw == null ? null : raw.toString();
        if (content == null || content.isBlank()) {
            return ToolResponse.failure("content 为空，未写入任何文件");
        }

        String className = JavaSourceUtils.extractMainClassName(content);
        if (className == null) {
            return ToolResponse.failure(
                    "content 里找不到类型声明（class/interface/enum/record），未写入");
        }

        try {
            Path target = baseDir.resolve(className + ".java").normalize();
            // 双保险：className 来自正则捕获的 \w+，理论上不含分隔符，仍确认没有越界
            if (!target.startsWith(baseDir)) {
                return ToolResponse.failure("拒绝写到用例目录之外: " + target);
            }
            Files.createDirectories(baseDir);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            writtenFiles.add(target.toFile());

            boolean hasTests = content.contains("@Test");
            LoggerUtil.logExec(Level.INFO, "写入测试文件: " + target
                    + (hasTests ? "（含 @Test）" : "（辅助类）"));
            return ToolResponse.success("已写入 " + target.getFileName()
                    + (hasTests ? "（含 @Test，将被执行）" : "（辅助类，仅参与编译）"));
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "写入测试文件失败: " + className + " - " + e.getMessage());
            return ToolResponse.failure("写入失败: " + e.getMessage());
        }
    }

    /** 本工具本轮写出的文件（按写入顺序） */
    public List<File> getWrittenFiles() {
        return List.copyOf(writtenFiles);
    }

    public boolean wroteAnything() {
        return !writtenFiles.isEmpty();
    }
}
