package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 文件复制工具
 */
public class CopyFileTool implements Tool<String> {

    @Override
    public String getName() {
        return "copy_file";
    }

    @Override
    public String getDescription() {
        return "Copy a file. Both source and destination file paths can be absolute or relative.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("sourcePath", "destinationPath");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "sourcePath", "The path of the source file, which can be absolute or relative.",
                "destinationPath", "The path of the destination file, which can be absolute or relative."
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "sourcePath", "string",
                "destinationPath", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (!args.containsKey("sourcePath") || !args.containsKey("destinationPath")) {
            LoggerUtil.logExec(Level.SEVERE, "参数错误，必须提供 'sourcePath' 和 'destinationPath' 参数: " + args);
            return ToolResponse.failure("参数错误，必须提供 'sourcePath' 和 'destinationPath' 参数");
        }
        String sourcePathStr = (String) args.get("sourcePath");
        String destinationPathStr = (String) args.get("destinationPath");

        try {
            Path sourcePath = Paths.get(sourcePathStr);
            Path destinationPath = Paths.get(destinationPathStr);

            if (!Files.exists(sourcePath)) {
                LoggerUtil.logExec(Level.WARNING, "源文件不存在: " + sourcePathStr);
                return ToolResponse.failure("源文件不存在: " + sourcePathStr);
            }
            if (Files.isDirectory(sourcePath)) {
                LoggerUtil.logExec(Level.WARNING, "源路径是一个目录，而不是文件: " + sourcePathStr);
                return ToolResponse.failure("源路径是一个目录，而不是文件: " + sourcePathStr);
            }

            // 确保目标目录存在
            if (destinationPath.getParent() != null) {
                Files.createDirectories(destinationPath.getParent());
            }

            Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            LoggerUtil.logExec(Level.INFO, "成功复制文件从 " + sourcePathStr + " 到 " + destinationPathStr);
            return ToolResponse.success("文件复制成功从 " + sourcePathStr + " 到 " + destinationPathStr);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "复制文件失败: " + e.getMessage());
            return ToolResponse.failure("复制文件失败: " + e.getMessage());
        } catch (SecurityException e) {
            LoggerUtil.logExec(Level.SEVERE, "安全权限不足，无法复制文件: " + e.getMessage());
            return ToolResponse.failure("安全权限不足，无法复制文件: " + e.getMessage());
        }
    }
}
