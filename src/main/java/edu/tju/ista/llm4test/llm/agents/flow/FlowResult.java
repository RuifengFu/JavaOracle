package edu.tju.ista.llm4test.llm.agents.flow;

import java.util.List;

/**
 * 流程执行结果
 */
public class FlowResult {
    private final boolean completed;
    private final String result;
    private final List<FlowStep> steps;
    
    public FlowResult(boolean completed, String result, List<FlowStep> steps) {
        this.completed = completed;
        this.result = result;
        this.steps = steps;
    }
    
    public boolean isCompleted() {
        return completed;
    }
    
    public String getResult() {
        return result;
    }
    
    public List<FlowStep> getSteps() {
        return steps;
    }
} 