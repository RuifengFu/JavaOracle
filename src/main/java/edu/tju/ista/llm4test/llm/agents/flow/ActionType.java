package edu.tju.ista.llm4test.llm.agents.flow;

/**
 * Defines the type of action a FlowStep can perform.
 */
public enum ActionType {
    /**
     * An action that calls a Large Language Model.
     */
    LLM_CALL,

    /**
     * An action that executes a tool from the ToolRegistry.
     */
    TOOL_CALL,

    /**
     * An action that manipulates the FlowContext directly (e.g., setting a variable).
     */
    CONTEXT_MANIPULATION
} 