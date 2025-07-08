package edu.tju.ista.llm4test.llm.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class ToolCall {
    public String toolName;
    public Map<String, Object> arguments;

    public ToolCall(String toolName, String args) throws JsonProcessingException {
        this.toolName = toolName;
        this.arguments = new ObjectMapper().readValue(args, Map.class);
    }

    public ToolCall(String toolName, Map<String, Object> args)  {
        this.toolName = toolName;
        this.arguments = args;
    }

    @Override
    public String toString() {
        return "ToolCall{" +
               "toolName='" + toolName + '\'' +
               ", arguments=" + arguments +
               '}';
    }
}