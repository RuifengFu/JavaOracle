package edu.tju.ista.llm4test.llm.agents.flow;

/**
 * 流程操作，表示代理在每一步决定执行的操作
 */
public class FlowAction {
    private final ActionType type;
    private final String content;
    private final String toolName;
    private final String toolInput;
    
    private FlowAction(ActionType type, String content, String toolName, String toolInput) {
        this.type = type;
        this.content = content;
        this.toolName = toolName;
        this.toolInput = toolInput;
    }
    
    public static FlowAction thinking(String thought) {
        return new FlowAction(ActionType.THINKING, thought, null, null);
    }
    
    public static FlowAction useTool(String toolName, String toolInput) {
        return new FlowAction(ActionType.USE_TOOL, null, toolName, toolInput);
    }
    
    public static FlowAction finalAnswer(String answer) {
        return new FlowAction(ActionType.FINAL_ANSWER, answer, null, null);
    }
    
    public ActionType getType() {
        return type;
    }
    
    public String getContent() {
        return content;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public String getToolInput() {
        return toolInput;
    }
} 