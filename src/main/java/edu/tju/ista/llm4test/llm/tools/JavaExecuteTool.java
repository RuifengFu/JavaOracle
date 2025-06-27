package edu.tju.ista.llm4test.llm.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
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
    public ToolResponse<String> execute(String input) {
        try {
            String className = input.trim();
            
            // 构建Java命令
            List<String> command = new ArrayList<>();
            command.add("java");
            
            // 添加类路径 (根据实际情况修改)
            command.add("-cp");
            command.add("./target/classes:./target/test-classes");
            
            // 添加类名
            command.add(className);
            
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