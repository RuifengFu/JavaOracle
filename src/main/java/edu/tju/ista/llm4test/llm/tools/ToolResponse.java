package edu.tju.ista.llm4test.llm.tools;

import java.util.HashMap;
import java.util.Map;

public class ToolResponse<T> {
    private final boolean success;
    private final T result;
    private final String errorMessage;
    private final Map<String, Object> metadata;

    // Private constructor
    private ToolResponse(boolean success, T result, String errorMessage) {
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
        this.metadata = new HashMap<>();
    }

    // Static factory method for success
    public static <T> ToolResponse<T> success(T result) {
        return new ToolResponse<>(true, result, null);
    }

    // Static factory method for failure
    public static <T> ToolResponse<T> failure(String errorMessage) {
        return new ToolResponse<>(false, null, errorMessage);
    }

    // Add metadata method
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    // Getter methods
    public boolean isSuccess() { return success; }
    public T getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }
    public Map<String, Object> getMetadata() { return metadata; }

    // Convert result to string for LLM interaction
    public String toString() {
        if (success) {
            return result != null ? result.toString() : "Success with no output";
        } else {
            return "Error: " + errorMessage;
        }
    }

    // Type conversion helper method
    public <T> T getResultAs(Class<T> type) {
        if (!success || result == null) {
            return null;
        }
        if (type.isInstance(result)) {
            return type.cast(result);
        }
        throw new ClassCastException("Cannot cast result to " + type.getName());
    }
}