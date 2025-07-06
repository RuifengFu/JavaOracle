package edu.tju.ista.llm4test.llm.agents.flow;

import java.util.Map;

/**
 * Represents a single step in an agent's workflow.
 * It contains an action to be performed and logic to determine the next step.
 */
public class FlowStep {
    private String id;
    private String description;
    private FlowAction action;
    private boolean isErrorHandler;

    // --- Fields for control flow ---
    /**
     * The ID of the next step to execute in a simple linear flow.
     * This is ignored if conditionKey is set.
     */
    private String nextStepId;

    /**
     * The key in FlowContext whose value will be used to determine the next step.
     * If this is set, the branching logic is activated.
     */
    private String conditionKey;

    /**
     * A map from a possible value of the context entry (identified by conditionKey)
     * to the ID of the next step to execute.
     */
    private Map<String, String> conditionalNextSteps;

    /**
     * The default next step ID to use if the value from the context does not match
     * any key in conditionalNextSteps, or if the conditionKey is not in the context.
     * If conditionKey is set and this is null, the flow terminates if no condition matches.
     */
    private String defaultNextStepId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public FlowAction getAction() {
        return action;
    }

    public void setAction(FlowAction action) {
        this.action = action;
    }

    public boolean isErrorHandler() {
        return isErrorHandler;
    }

    public void setErrorHandler(boolean errorHandler) {
        isErrorHandler = errorHandler;
    }

    public String getNextStepId() {
        return nextStepId;
    }

    public void setNextStepId(String nextStepId) {
        this.nextStepId = nextStepId;
    }

    public String getConditionKey() {
        return conditionKey;
    }

    public void setConditionKey(String conditionKey) {
        this.conditionKey = conditionKey;
    }

    public Map<String, String> getConditionalNextSteps() {
        return conditionalNextSteps;
    }

    public void setConditionalNextSteps(Map<String, String> conditionalNextSteps) {
        this.conditionalNextSteps = conditionalNextSteps;
    }

    public String getDefaultNextStepId() {
        return defaultNextStepId;
    }

    public void setDefaultNextStepId(String defaultNextStepId) {
        this.defaultNextStepId = defaultNextStepId;
    }
} 