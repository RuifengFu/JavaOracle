package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

public class JDKDocConformanceTool implements Tool<Void> {
    @Override
    public String getName() {
        return "jdk_doc_conformance_check";
    }

    @Override
    public String getDescription() {
        return "Verify consistency between JDK method implementations and their JavaDoc specifications";
    }

    @Override
    public List<String> getParameters() {
        return List.of("inconsistencies", "severity", "confidence_score");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "inconsistencies", "List of detected discrepancies",
                "severity", "Issue severity level(CRITICAL, WARNING, INFO, NO)",
                "confidence_score", "Analysis confidence rating (0.0-1.0), 1.0 being the most confident"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "inconsistencies", "string",
                "severity", "string",
                "confidence_score", "string"
        );
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        // This tool is for output only, so it doesn't do anything.
        return ToolResponse.success(null);
    }
}
