package edu.tju.ista.llm4test.llm.agents.flow;

/**
 * Represents the final result of an AgentFlow execution.
 */
public class FlowResult {
    private final boolean success;
    private final String errorMessage;
    private final FlowContext context;

    private FlowResult(boolean success, String errorMessage, FlowContext context) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.context = context;
    }

    /**
     * Creates a success result.
     * @param context The final context after execution.
     * @return A new FlowResult instance.
     */
    public static FlowResult success(FlowContext context) {
        return new FlowResult(true, null, context);
    }

    /**
     * Creates a failure result.
     * @param errorMessage A message describing the failure.
     * @return A new FlowResult instance.
     */
    public static FlowResult failure(String errorMessage) {
        return new FlowResult(false, errorMessage, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public FlowContext getContext() {
        return context;
    }
} 