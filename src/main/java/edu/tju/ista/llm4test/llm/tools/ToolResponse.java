package edu.tju.ista.llm4test.llm.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行响应
 * @param <T> 响应结果类型
 */
public class ToolResponse<T> {
    private final boolean success;
    private final String message;
    private final T result;
    private final Map<String, Object> metadata;

    // Private constructor
    private ToolResponse(boolean success, String message, T result) {
        this.success = success;
        this.message = message;
        this.result = result;
        this.metadata = new HashMap<>();
    }

    /**
     * 创建成功响应
     */
    public static <T> ToolResponse<T> success(T result) {
        return new ToolResponse<>(true, "执行成功", result);
    }

    /**
     * 创建失败响应
     */
    public static <T> ToolResponse<T> failure(String message) {
        return new ToolResponse<>(false, message, null);
    }

    // Add metadata method
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getResult() {
        return result;
    }

    @Override
    public String toString() {
        if (success) {
            return result == null ? "成功，无返回结果" : result.toString();
        } else {
            return "失败: " + message;
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