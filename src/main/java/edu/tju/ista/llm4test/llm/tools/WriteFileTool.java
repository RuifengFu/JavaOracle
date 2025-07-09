package edu.tju.ista.llm4test.llm.tools;


import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 文件写入工具
 */
public class WriteFileTool implements Tool<String> {

    @Override
    public String getName() {
        return "write_to_file";
    }

    @Override
    public String getDescription() {
        return "Write content to a file, this operation will overwrite the file if it exists.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("path", "content");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "path", "The file path where the content will be written",
                "content", "The content to write into the file"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "path", "string",
                "content", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        // 解析输入参数
        if (args.size() != 2 || !args.containsKey("path") || !args.containsKey("content")) {
            LoggerUtil.logExec(Level.SEVERE, "参数错误，必须提供文件路径和内容: " + args);
            return ToolResponse.failure("参数错误，必须提供文件路径和内容");
        }
        String filePath = (String) args.get("path");
        String content = (String) args.get("content");

        return execute(filePath, content);
    }

    public ToolResponse<String> execute(String filePath, String content) {

        try {
            Path path = Paths.get(filePath);
            // 确保父目录存在
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            LoggerUtil.logExec(Level.INFO, "成功写入文件: " + filePath);
            return ToolResponse.success("文件写入成功: " + filePath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "写入文件失败: " + filePath + " - " + e.getMessage());
            return ToolResponse.failure("写入文件失败: " + e.getMessage());
        } catch (SecurityException e) {
            LoggerUtil.logExec(Level.SEVERE, "安全权限不足，无法写入文件: " + filePath + " - " + e.getMessage());
            return ToolResponse.failure("安全权限不足，无法写入文件: " + e.getMessage());
        }
    }


}
