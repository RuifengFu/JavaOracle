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
 * 目录创建工具
 */
public class CreateDirectoryTool implements Tool<String> {

    @Override
    public String getName() {
        return "create_directory";
    }

    @Override
    public String getDescription() {
        return "创建指定路径的目录，包括所有不存在的父目录。路径可以是绝对路径或相对路径。";
    }

    @Override
    public List<String> getParameters() {
        return List.of("directoryPath");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "directoryPath", "要创建的目录的路径，可以是绝对路径或相对路径。"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "directoryPath", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (!args.containsKey("directoryPath")) {
            LoggerUtil.logExec(Level.SEVERE, "参数错误，必须提供 'directoryPath' 参数: " + args);
            return ToolResponse.failure("参数错误，必须提供 'directoryPath' 参数");
        }
        String directoryPath = (String) args.get("directoryPath");

        try {
            Path path = Paths.get(directoryPath);
            Files.createDirectories(path);
            LoggerUtil.logExec(Level.INFO, "成功创建目录: " + directoryPath);
            return ToolResponse.success("目录创建成功: " + directoryPath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "创建目录失败: " + directoryPath + " - " + e.getMessage());
            return ToolResponse.failure("创建目录失败: " + e.getMessage());
        } catch (SecurityException e) {
            LoggerUtil.logExec(Level.SEVERE, "安全权限不足，无法创建目录: " + directoryPath + " - " + e.getMessage());
            return ToolResponse.failure("安全权限不足，无法创建目录: " + directoryPath + " - " + e.getMessage());
        }
    }
}
