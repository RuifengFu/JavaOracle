package edu.tju.ista.llm4test;

import edu.tju.ista.llm4test.config.ApplicationConfig;
import edu.tju.ista.llm4test.service.CommandHandler;

/**
 * 应用程序主入口
 * 负责解析命令行参数并路由到相应的处理器
 */
public class Main {

    public static void main(String[] args) {
        // 设置系统属性
        if (ApplicationConfig.isUseSystemProxies()) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        
        // 验证参数
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }
        
        // 输出参数信息
        System.out.println("执行参数:");
        for (String arg : args) {
            System.out.println("  " + arg);
        }
        
        try {
            // 根据命令类型路由到相应的处理器
            String command = args[0];
            switch (command) {
                case "execute":
                    validateArgumentCount(args, 2, "execute命令需要指定测试路径");
                    CommandHandler.handleExecuteCommand(args[1]);
                    break;
                    
                case "generate":
                    validateArgumentCount(args, 2, "generate命令需要指定测试路径");
                    CommandHandler.handleGenerateCommand(args[1]);
                    break;
                    
                case "env":
                    CommandHandler.handleEnvironmentCommand();
                    break;
                    
                case "verify":
                    CommandHandler.handleVerifyCommand();
                    break;
                    
                default:
                    System.err.println("未知命令: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 验证参数数量
     * 
     * @param args 参数数组
     * @param expectedCount 期望的参数数量
     * @param errorMessage 错误消息
     */
    private static void validateArgumentCount(String[] args, int expectedCount, String errorMessage) {
        if (args.length < expectedCount) {
            System.err.println(errorMessage);
            printUsage();
            System.exit(1);
        }
    }
    
    /**
     * 打印使用说明
     */
    private static void printUsage() {
        System.out.println("用法:");
        System.out.println("  java -jar app.jar execute <测试路径>   - 执行指定路径的测试");
        System.out.println("  java -jar app.jar generate <测试路径>  - 生成指定路径的测试用例");
        System.out.println("  java -jar app.jar env                  - 测试JDK环境");
        System.out.println("  java -jar app.jar verify               - 验证已识别的Bug");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar app.jar execute java/util/ArrayList");
        System.out.println("  java -jar app.jar generate java/util/HashMap");
    }
}



