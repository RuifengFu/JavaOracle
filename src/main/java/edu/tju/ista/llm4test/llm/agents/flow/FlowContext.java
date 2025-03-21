package edu.tju.ista.llm4test.llm.agents.flow;

import edu.tju.ista.llm4test.llm.tools.ToolResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程上下文，存储流程执行过程中的状态和历史信息
 */
public class FlowContext {
    private final Map<String, Object> input;              // 初始输入数据
    private final List<String> observations;              // 观察结果列表
    private final List<String> thinking;                  // 思考过程
    private final List<Map<String, Object>> toolResults;  // 工具调用结果
    
    public FlowContext(Map<String, Object> input) {
        this.input = new HashMap<>(input);
        this.observations = new ArrayList<>();
        this.thinking = new ArrayList<>();
        this.toolResults = new ArrayList<>();
    }
    
    public void addObservation(String observation) {
        observations.add(observation);
    }
    
    public void addThinking(String thought) {
        thinking.add(thought);
    }
    
    public void addToolResult(String toolName, String toolInput, ToolResponse<?> result) {
        Map<String, Object> toolResult = new HashMap<>();
        toolResult.put("tool", toolName);
        toolResult.put("input", toolInput);
        toolResult.put("success", result.isSuccess());
        toolResult.put("result", result.toString());
        toolResults.add(toolResult);
    }
    
    public Map<String, Object> getInput() {
        return input;
    }
    
    public List<String> getObservations() {
        return observations;
    }
    
    public List<String> getThinking() {
        return thinking;
    }
    
    public List<Map<String, Object>> getToolResults() {
        return toolResults;
    }
} 