package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

public class RootCauseOutputTool implements Tool<Void> {
    @Override
    public String getName() {
        return "root_cause_analysis";
    }

    @Override
    public String getDescription() {
        return "Analyze the root cause of a bug";
    }

    @Override
    public List<String> getParameters() {
        return List.of("root_cause", "bug_location", "bug_type", "fix_advice", "evidence", "report_bug");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "root_cause", "The root cause of the bug",
                "bug_location", "The location of the bug",
                "bug_type", "The type of the bug",
                "fix_advice", "The advice to fix the bug",
                "evidence", "Specific evidence from the test case, output, or API documentation that supports the issue claim",
                "report_bug", "Whether to report the bug to Java Community"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "root_cause", "string",
                "bug_location", "string",
                "bug_type", "string",
                "fix_advice", "string",
                "evidence", "string",
                "report_bug", "boolean"
        );
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        // This tool is for output only, so it doesn't do anything.
        return ToolResponse.success(null);
    }
}
