package edu.tju.ista.llm4test.llm.agents.flow;

/**
 * 操作类型枚举
 */
public enum ActionType {
    THINKING,     // 思考阶段，不执行具体操作
    USE_TOOL,     // 使用工具
    FINAL_ANSWER  // 最终答案，流程结束
} 