package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.ProcessRunner;
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
            
            // 走 ProcessRunner：原先是先把流读到 EOF 再 waitFor(600s)，
            // 被测代码一旦挂住（或输出量大）就永远停在 lines() 上，超时形同虚设
            ProcessRunner.Result run = ProcessRunner.run(command, TimeUnit.SECONDS.toMillis(600));
            String output = run.stdout() + run.stderr();

            if (run.timedOut()) {
                return ToolResponse.failure("执行超时");
            }

            int exitCode = run.exitValue();
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