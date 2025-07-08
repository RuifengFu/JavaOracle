package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

public class BasicTool implements Tool<Void>{
    String name;
    String description;

    public BasicTool(String name, String description) {
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
        return List.of();
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of();
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of();
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        return null;
    }
}
