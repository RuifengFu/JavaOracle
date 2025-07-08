package edu.tju.ista.llm4test.llm.tools;

import java.util.HashMap;
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

    default Map<String, Object> toMap() {
        var parameters = getParameters();
        var parametersType = getParametersType();
        var parametersDescription = getParametersDescription();
        if (parameters.size() != parametersDescription.size() || parameters.size() != parametersType.size()) {
            throw new IllegalArgumentException("参数列表、类型和描述必须一致");
        }

        HashMap<String, Object> map = new HashMap<>();
        HashMap<String, Object> funcMap = new HashMap<>();
        HashMap<String, Object> paraMap = new HashMap<>();
        HashMap<String, Object> properties = new HashMap<>();
        String[] required = parameters.toArray(new String[0]);

        map.put("type", "function");
        map.put("function", funcMap);

        funcMap.put("name", getName());
        funcMap.put("description", getDescription());
        funcMap.put("parameters", paraMap);

        paraMap.put("type", "object");
        paraMap.put("properties", properties);
        paraMap.put("required", required);

        for (String param : parameters) {
            Map<String, Object> nestedMap = new HashMap<>();
            nestedMap.put("type", parametersType.get(param));
            nestedMap.put("description", parametersDescription.get(param));
            properties.put(param, nestedMap);
        }
        return map;
    }
}