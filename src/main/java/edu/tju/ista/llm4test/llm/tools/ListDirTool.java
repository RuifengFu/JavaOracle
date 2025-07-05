package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static edu.tju.ista.llm4test.llm.tools.ToolResponse.failure;
import static edu.tju.ista.llm4test.llm.tools.ToolResponse.success;

public class ListDirTool implements Tool<String> {

    @Override
    public String getName() {
        return "list_directory";
    }

    @Override
    public String getDescription() {
        return "Lists the files and subdirectories in a specified directory.";
    }

    @Override
    public List<String> getParameters() {
        return Collections.singletonList("directory_path");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("directory_path", "The path of the directory to list.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("directory_path", "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> parameters) {
        String path = (String) parameters.get("directory_path");
        if (path == null || path.isEmpty()) {
            return failure("Error: 'directory_path' parameter is required and cannot be empty.");
        }

        File directory = new File(path);

        if (!directory.exists()) {
            return failure("Error: Directory does not exist at path: " + path);
        }

        if (!directory.isDirectory()) {
            return failure("Error: Path is not a directory: " + path);
        }

        try {
            String[] contents = directory.list();
            if (contents == null) {
                return failure("Error: Failed to list contents of directory: " + path);
            }
            String result = String.join("\n", contents);
            return success(result);
        } catch (SecurityException e) {
            LoggerUtil.logExec(Level.SEVERE, "SecurityException while listing directory: " + path + " " +  e.getMessage());
            return failure("Error: Security permissions prevent access to the directory.");
        }
    }
} 