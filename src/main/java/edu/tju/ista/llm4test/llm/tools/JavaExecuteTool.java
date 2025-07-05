package edu.tju.ista.llm4test.llm.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 直接使用Java执行测试用例的工具
 */
public class JavaExecuteTool implements Tool<String> {
    
    @Override
    public String getName() {
        return "java_execute";
    }
    
    @Override
    public String getDescription() {
        return "直接使用Java命令执行指定的测试类，返回执行输出结果";
    }

    @Override
    public List<String> getParameters() {
        return List.of("className");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("className", "The name of the test class to execute");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("className", "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("className") || !(args.get("className") instanceof String)) {
            return ToolResponse.failure("参数错误，必须提供 className 且其类型为 String");
        }
        String className = (String) args.get("className");
        return execute(className);
    }
    
    public ToolResponse<String> execute(String className) {
        try {
            String trimmedClassName = className.trim();
            
            // 构建Java命令
            List<String> command = new ArrayList<>();
            command.add("java");
            
            // TODO: CHECK THIS
            // 添加类路径 (根据实际情况修改)
            command.add("-cp");
            command.add("./target/classes:./target/test-classes");
            
            // 添加类名
            command.add(trimmedClassName);
            
            // 创建进程
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // 合并标准输出和错误输出
            
            // 启动进程
            Process process = pb.start();
            
            // 读取输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.lines().collect(Collectors.joining("\n"));
            
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                process.destroyForcibly();
                return ToolResponse.failure("执行超时");
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ToolResponse.success(output);
            } else {
                return ToolResponse.failure("执行失败，退出码: " + exitCode + "\n输出: " + output);
            }
        } catch (Exception e) {
            return ToolResponse.failure("执行异常: " + e.getMessage());
        }
    }
} 