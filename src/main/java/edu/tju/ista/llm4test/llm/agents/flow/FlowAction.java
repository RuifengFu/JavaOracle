package edu.tju.ista.llm4test.llm.agents.flow;

import java.util.Map;

/**
 * Represents a specific action to be performed within a FlowStep.
 */
public class FlowAction {
    private ActionType type;
    private String promptTemplate; // For LLM_CALL
    private String toolName;       // For TOOL_CALL
    private Map<String, Object> parameters; // For TOOL_CALL and CONTEXT_MANIPULATION
    private String outputKey;      // The key to store the output in the context

    public ActionType getType() {
        return type;
    }

    public void setType(ActionType type) {
        this.type = type;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }
} 