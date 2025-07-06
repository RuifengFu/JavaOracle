package edu.tju.ista.llm4test.llm.agents.flow;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a workflow definition for an agent.
 * This class holds the structure of the flow, composed of steps,
 * but does not contain the logic for execution.
 */
public class AgentFlow {
    private final String name;
    private final String startStepId;
    private final Map<String, FlowStep> steps;

    /**
     * Creates a new workflow definition.
     *
     * @param name        The name of the flow.
     * @param startStepId The ID of the first step to be executed.
     */
    public AgentFlow(String name, String startStepId) {
        this.name = Objects.requireNonNull(name, "Flow name cannot be null");
        this.startStepId = Objects.requireNonNull(startStepId, "Start step ID cannot be null");
        this.steps = new HashMap<>();
    }

    /**
     * Adds a step to the workflow.
     *
     * @param step The step to add.
     */
    public void addStep(FlowStep step) {
        Objects.requireNonNull(step, "Step cannot be null");
        Objects.requireNonNull(step.getId(), "Step ID cannot be null");
        steps.put(step.getId(), step);
    }

    /**
     * Retrieves a step by its ID.
     *
     * @param stepId The ID of the step.
     * @return The FlowStep, or null if not found.
     */
    public FlowStep getStep(String stepId) {
        return steps.get(stepId);
    }

    public String getName() {
        return name;
    }

    public String getStartStepId() {
        return startStepId;
    }
} 