package edu.tju.ista.llm4test.llm.tools;

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
 * 改测试文件：精确匹配一段旧文本，替换成新文本（供 LLM 以 tool call 方式做增量修改）。
 * <p>
 * 刻意<b>不</b>用 unified diff：行号与 hunk 头（{@code @@ -120,7 +120,9 @@}）是模型
 * 最容易算错的部分，而算错的后果往往是**部分应用**——文件被改成一个谁也没预期的中间态。
 * 「唯一命中就替换、否则拒绝」只有两种结果，没有中间态。
 * <p>
 * 相对整份重写（{@link WriteTestFileTool}）的好处：
 * <ul>
 *   <li><b>省输出额度</b>：{@code FieldUtilsTest} 有 948 行 / 60KB，整份重写要吐掉
 *       约 15k token；推理模型的 completion 额度被 reasoning 吃掉之后很容易截断。</li>
 *   <li><b>不动的地方天然不动</b>：license 头、与本次改动无关的用例都原样保留——
 *       整份重写时模型不会把 Apache license 头带回来，这是实测过的。</li>
 * </ul>
 * 匹配要求 {@code old_str} 在文件中<b>恰好出现一次</b>：出现多次说明上下文不足以定位，
 * 此时替换哪一处都可能是错的，因此要求模型带上更多上下文重试。
 */
public class EditTestFileTool implements Tool<String> {

    public static final String TOOL_NAME = "edit_test_file";
    public static final String PARAM_FILE = "file_name";
    public static final String PARAM_OLD = "old_str";
    public static final String PARAM_NEW = "new_str";

    /** 用例所在目录；模型只能改这里面的文件 */
    private final Path baseDir;
    /** 缺省被编辑的文件（用例主文件），单文件用例时模型可以不传 file_name */
    private final String defaultFileName;

    private final List<File> editedFiles = new ArrayList<>();

    public EditTestFileTool(Path baseDir, String defaultFileName) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.defaultFileName = defaultFileName;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Edit the test case in place by replacing an exact snippet of it. Prefer this over "
                + "rewriting a whole file: it keeps everything you do not touch (license header, "
                + "unrelated test methods) byte-for-byte intact and costs far fewer output tokens. "
                + "old_str must appear EXACTLY ONCE in the file - include enough surrounding lines to "
                + "make it unique, and copy it verbatim including indentation. To insert new test "
                + "methods, match the final closing brace of the class together with the method above "
                + "it and put both back in new_str. Call this tool several times for several edits.";
    }

    @Override
    public List<String> getParameters() {
        return List.of(PARAM_FILE, PARAM_OLD, PARAM_NEW);
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                PARAM_FILE, "Name of the file to edit, e.g. FooTest.java. Optional for a single-file "
                        + "test case: defaults to the test case's own file.",
                PARAM_OLD, "The exact text to replace, copied verbatim from the file including "
                        + "indentation. Must occur exactly once.",
                PARAM_NEW, "The replacement text. Use an empty string to delete the matched text.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(PARAM_FILE, "string", PARAM_OLD, "string", PARAM_NEW, "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey(PARAM_OLD) || !args.containsKey(PARAM_NEW)) {
            return ToolResponse.failure("参数错误，必须提供 " + PARAM_OLD + " 与 " + PARAM_NEW);
        }
        String oldStr = asString(args.get(PARAM_OLD));
        String newStr = asString(args.get(PARAM_NEW));
        if (oldStr == null || oldStr.isEmpty()) {
            return ToolResponse.failure(PARAM_OLD + " 为空，无法定位要替换的位置");
        }
        if (newStr == null) {
            newStr = "";
        }
        if (oldStr.equals(newStr)) {
            return ToolResponse.failure("old_str 与 new_str 相同，没有产生任何改动");
        }

        String requested = asString(args.get(PARAM_FILE));
        String fileName = (requested == null || requested.isBlank()) ? defaultFileName : requested.trim();
        if (fileName == null) {
            return ToolResponse.failure("未指定 " + PARAM_FILE + "，且该用例没有默认文件");
        }
        // 只接受简单文件名，杜绝 ../ 或绝对路径
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ToolResponse.failure("file_name 只能是用例目录下的文件名: " + fileName);
        }

        try {
            Path target = baseDir.resolve(fileName).normalize();
            if (!target.startsWith(baseDir) || !Files.isRegularFile(target)) {
                return ToolResponse.failure("文件不存在于用例目录: " + fileName);
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            int first = content.indexOf(oldStr);
            if (first < 0) {
                return ToolResponse.failure("old_str 在 " + fileName
                        + " 中找不到（注意必须逐字复制，含缩进）；未做任何修改");
            }
            if (content.indexOf(oldStr, first + 1) >= 0) {
                long count = content.split(java.util.regex.Pattern.quote(oldStr), -1).length - 1;
                return ToolResponse.failure("old_str 在 " + fileName + " 中出现 " + count
                        + " 次，无法唯一定位；请带上更多上下文重试。未做任何修改");
            }

            String updated = content.substring(0, first) + newStr + content.substring(first + oldStr.length());
            Files.writeString(target, updated, StandardCharsets.UTF_8);
            if (!editedFiles.contains(target.toFile())) {
                editedFiles.add(target.toFile());
            }
            LoggerUtil.logExec(Level.INFO, String.format(
                    "编辑测试文件: %s（-%d 字 / +%d 字）", target.getFileName(), oldStr.length(), newStr.length()));
            return ToolResponse.success("已修改 " + fileName);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "编辑测试文件失败: " + fileName + " - " + e.getMessage());
            return ToolResponse.failure("编辑失败: " + e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 本轮被改过的文件 */
    public List<File> getEditedFiles() {
        return List.copyOf(editedFiles);
    }

    public boolean editedAnything() {
        return !editedFiles.isEmpty();
    }
}
