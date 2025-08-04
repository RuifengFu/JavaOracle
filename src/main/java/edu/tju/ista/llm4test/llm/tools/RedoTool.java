package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.execute.TestResult;

import java.util.List;
import java.util.Map;

/**
 * A specialized tool for the "redo" action, which requires feedback.
 */
public class RedoTool implements Tool<Void> {
    private final String name;
    private final String description;

    public RedoTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public List<String> getParameters() {
        return List.of("feedback");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("feedback", "A detailed, actionable explanation of why the last simplification was wrong and what to try next. And something important for future reduction.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("feedback", "string");
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        // This tool does not perform any real action; it's a signal.
        // The feedback is extracted by the agent from the tool call arguments.
        return ToolResponse.success(null);
    }
}
