package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 文件读取工具
 */
public class ReadFileTool implements Tool<String> {

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取指定文件的内容。文件路径可以是绝对路径或相对路径。";
    }

    @Override
    public List<String> getParameters() {
        return List.of("filePath");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "filePath", "要读取的文件的路径，可以是绝对路径或相对路径。"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "filePath", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (!args.containsKey("filePath")) {
            LoggerUtil.logExec(Level.SEVERE, "参数错误，必须提供 'filePath' 参数: " + args);
            return ToolResponse.failure("参数错误，必须提供 'filePath' 参数");
        }
        String filePath = (String) args.get("filePath");

        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                LoggerUtil.logExec(Level.WARNING, "文件不存在: " + filePath);
                return ToolResponse.failure("文件不存在: " + filePath);
            }
            if (Files.isDirectory(path)) {
                LoggerUtil.logExec(Level.WARNING, "输入是一个目录，而不是文件: " + filePath);
                return ToolResponse.failure("输入是一个目录，而不是文件: " + filePath);
            }
            String content = Files.readString(path);
            LoggerUtil.logExec(Level.INFO, "成功读取文件: " + filePath);
            return ToolResponse.success(content);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "读取文件失败: " + filePath + " - " + e.getMessage());
            return ToolResponse.failure("读取文件失败: " + e.getMessage());
        } catch (SecurityException e) {
            LoggerUtil.logExec(Level.SEVERE, "安全权限不足，无法读取文件: " + filePath + " - " + e.getMessage());
            return ToolResponse.failure("安全权限不足，无法读取文件: " + e.getMessage());
        }
    }
}
