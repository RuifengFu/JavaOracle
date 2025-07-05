package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;

import java.util.List;
import java.util.Map;

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

    List<String> getParameters();
    Map<String, String> getParametersDescription();
    Map<String, String> getParametersType();
    
    /**
     * 执行工具
     * @param args 工具输入参数
     * @return 工具执行结果
     */
    ToolResponse<T> execute(Map<String, Object> args);

    default FuncTool getTool() {
        var parameters = getParameters();
        var parametersType = getParametersType();
        var parametersDescription = getParametersDescription();
        if (parameters.size() != parametersDescription.size() || parameters.size() != parametersType.size()) {
            throw new IllegalArgumentException("参数列表、类型和描述必须一致");
        }
        var tool = new FuncTool(getName(), getDescription());
        for (String param : parameters) {
            tool.addParameter(param, parametersType.get(param), parametersDescription.get(param));
        }
        return tool;
    }
}