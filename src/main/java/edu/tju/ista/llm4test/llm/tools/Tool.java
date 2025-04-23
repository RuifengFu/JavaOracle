package edu.tju.ista.llm4test.llm.tools;

import java.util.List;

/**
 * 工具接口，定义了Agent可以使用的工具
 * @param <T> 工具执行结果的类型
 */
public interface Tool<T> {
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 执行工具
     * @param input 工具输入参数
     * @return 工具执行结果
     */
    ToolResponse<T> execute(String input);
}