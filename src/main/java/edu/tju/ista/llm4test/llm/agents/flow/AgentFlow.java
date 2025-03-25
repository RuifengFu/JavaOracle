package edu.tju.ista.llm4test.llm.agents.flow;

import edu.tju.ista.llm4test.llm.agents.Agent;
import edu.tju.ista.llm4test.llm.memory.MemoryStore;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理系统的流程控制类，负责协调代理的思考和行动过程
 */
public class AgentFlow {
    private final Agent agent;  // 负责执行任务的代理
    private final Map<String, Tool<?>> tools;  // 代理可以使用的工具集
    private final List<FlowStep> flowHistory;  // 流程执行历史记录
    private final int maxIterations;  // 最大迭代次数，防止无限循环
    
    /**
     * 创建一个代理流程
     * @param agent 执行任务的代理
     * @param maxIterations 最大迭代次数
     */
    public AgentFlow(Agent agent, int maxIterations) {
        this.agent = agent;
        this.tools = new HashMap<>();
        this.flowHistory = new ArrayList<>();
        this.maxIterations = maxIterations;
    }


    public AgentFlow(Agent agent, int maxIterations, MemoryStore sharedMemory) {
        this.agent = agent;
        this.tools = new HashMap<>();
        this.flowHistory = new ArrayList<>();
        this.maxIterations = maxIterations;
    }

    /**
     * 注册代理可以使用的工具
     * @param tool 工具实例
     * @return 当前Flow实例，支持链式调用
     */
    public AgentFlow registerTool(Tool<?> tool) {
        this.tools.put(tool.getName(), tool);
        return this;
    }
    
    /**
     * 执行代理流程
     * @param input 输入数据
     * @return 流程执行结果
     */
    public FlowResult execute(Map<String, Object> input) {
        FlowContext context = new FlowContext(input);
        
        int iteration = 0;
        while (iteration < maxIterations) {
            // 1. 思考阶段 - 生成提示并获取代理响应
            String prompt = agent.genPrompt(buildPromptData(context));
            String response = agent.getLLM().messageCompletion(prompt, 0.7); // 使用适当的温度值
            
            // 2. 解析响应，确定下一步操作
            FlowAction action = parseResponse(response);
            flowHistory.add(new FlowStep(iteration, prompt, response, action));
            
            // 3. 根据操作类型执行相应的行为
            if (action.getType() == ActionType.FINAL_ANSWER) {
                // 任务完成，返回结果
                return new FlowResult(true, action.getContent(), flowHistory);
            } else if (action.getType() == ActionType.USE_TOOL) {
                // 调用工具
                String toolName = action.getToolName();
                String toolInput = action.getToolInput();
                
                if (!tools.containsKey(toolName)) {
                    context.addObservation("错误: 工具 '" + toolName + "' 不存在");
                    continue;
                }
                
                try {
                    Tool<?> tool = tools.get(toolName);
                    ToolResponse<?> toolResult = tool.execute(toolInput);
                    context.addToolResult(toolName, toolInput, toolResult);
                    context.addObservation("工具 '" + toolName + "' 执行结果: " + toolResult);
                } catch (Exception e) {
                    context.addObservation("错误: 工具 '" + toolName + "' 执行失败: " + e.getMessage());
                }
            } else if (action.getType() == ActionType.THINKING) {
                // 更新思考内容，不执行具体操作
                context.addThinking(action.getContent());
            }
            
            iteration++;
        }
        
        // 达到最大迭代次数仍未完成
        return new FlowResult(false, "达到最大迭代次数 (" + maxIterations + ")", flowHistory);
    }
    
    /**
     * 构建提示数据模型
     */
    private HashMap<String, Object> buildPromptData(FlowContext context) {
        HashMap<String, Object> dataModel = new HashMap<>(context.getInput());
        
        // 添加工具信息
        List<Map<String, String>> availableTools = new ArrayList<>();
        for (Tool<?> tool : tools.values()) {
            Map<String, String> toolInfo = new HashMap<>();
            toolInfo.put("name", tool.getName());
            toolInfo.put("description", tool.getDescription());
            availableTools.add(toolInfo);
        }
        dataModel.put("tools", availableTools);
        
        // 添加观察和思考历史
        dataModel.put("observations", context.getObservations());
        dataModel.put("thinking", context.getThinking());
        dataModel.put("tool_results", context.getToolResults());
        
        return dataModel;
    }
    
    /**
     * 解析代理响应，确定下一步操作
     */
    private FlowAction parseResponse(String response) {
        // 这里需要根据响应格式进行解析
        // 可以使用正则表达式或其他方法从文本中提取操作信息
        
        // 简单示例：检查是否包含工具调用或最终答案标记
        if (response.contains("工具调用:") || response.contains("TOOL:")) {
            // 简单解析，实际场景应更精确解析
            int toolStart = response.indexOf("工具调用:") >= 0 ? 
                            response.indexOf("工具调用:") : response.indexOf("TOOL:");
            int nameStart = response.indexOf("名称:", toolStart);
            int inputStart = response.indexOf("输入:", toolStart);
            
            if (nameStart >= 0 && inputStart >= 0) {
                String toolName = response.substring(nameStart + 3, inputStart).trim();
                String toolInput = response.substring(inputStart + 3).trim();
                return FlowAction.useTool(toolName, toolInput);
            }
        } else if (response.contains("最终答案:") || response.contains("ANSWER:")) {
            int answerStart = response.contains("最终答案:") ? 
                              response.indexOf("最终答案:") + 5 : response.indexOf("ANSWER:") + 7;
            String answer = response.substring(answerStart).trim();
            return FlowAction.finalAnswer(answer);
        }
        
        // 默认作为思考内容处理
        return FlowAction.thinking(response);
    }
} 