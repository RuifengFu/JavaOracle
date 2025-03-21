package edu.tju.ista.llm4test.llm.agents.flow;

/**
 * 流程步骤，记录每一步流程的详细信息
 */
public class FlowStep {
    private final int iteration;
    private final String prompt;
    private final String response;
    private final FlowAction action;
    
    public FlowStep(int iteration, String prompt, String response, FlowAction action) {
        this.iteration = iteration;
        this.prompt = prompt;
        this.response = response;
        this.action = action;
    }
    
    public int getIteration() {
        return iteration;
    }
    
    public String getPrompt() {
        return prompt;
    }
    
    public String getResponse() {
        return response;
    }
    
    public FlowAction getAction() {
        return action;
    }
} 