package edu.tju.ista.llm4test.llm.agents.flow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A generic context for an agent's workflow, acting as a "blackboard"
 * to store and retrieve data between flow steps.
 */
public class FlowContext {
    private final Map<String, Object> contextMap;

    /**
     * Creates an empty context.
     */
    public FlowContext() {
        this.contextMap = new HashMap<>();
    }

    /**
     * Puts a key-value pair into the context.
     *
     * @param key   the key.
     * @param value the value.
     */
    public void put(String key, Object value) {
        contextMap.put(key, value);
    }

    /**
     * Gets a value from the context by its key.
     *
     * @param key the key.
     * @return the value, or null if the key is not found.
     */
    public Object get(String key) {
        return contextMap.get(key);
    }

    /**
     * Gets an unmodifiable view of the entire context map.
     *
     * @return the full context map.
     */
    public Map<String, Object> getAll() {
        return Collections.unmodifiableMap(contextMap);
    }
} 