package edu.tju.ista.llm4test.llm.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * A registry for tools that can be used by an agent.
 * This allows for runtime registration of tools, handling tools with constructor dependencies.
 */
public class ToolRegistry {
    private final Map<String, Tool<?>> toolMap = new HashMap<>();

    /**
     * Registers a tool. If a tool with the same name already exists, it will be overwritten.
     * @param tool The tool to register.
     */
    public void register(Tool<?> tool) {
        toolMap.put(tool.getName(), tool);
    }

    /**
     * Retrieves a tool by its name.
     * @param name The name of the tool.
     * @return The tool instance.
     * @throws IllegalArgumentException if the tool is not found.
     */
    public Tool<?> get(String name) {
        Tool<?> tool = toolMap.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool;
    }

    /**
     * Checks if a tool with the given name is registered.
     * @param name The name of the tool.
     * @return true if the tool is registered, false otherwise.
     */
    public boolean has(String name) {
        return toolMap.containsKey(name);
    }

    /**
     * Returns a set of the names of all registered tools.
     * @return A set of tool names.
     */
    public Set<String> getToolNames() {
        return toolMap.keySet();
    }

    public ToolResponse<?> act(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "ToolCall cannot be null");
        Tool<?> tool = get(toolCall.toolName);
        return tool.execute(toolCall.arguments);
    }

    public boolean verifyArgs(Tool<?> tool, Map<String, Object> args) {
        if (args.size() != tool.getParameters().size()) {
            return false;
        }
        for (String param : tool.getParameters()) {
            if (!args.containsKey(param)) {
                return false;
            }
        }
        return true;
    }
} 