package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestExecutor;

import java.io.File;
import java.nio.file.Path;

/**
 * 命令处理器
 * 负责处理不同类型的命令行参数
 */
public class CommandHandler {

    /**
     * 处理execute命令
     * 
     * @param testPath 测试路径
     */
    public static void handleExecuteCommand(String testPath) {
        File resultDir = GlobalConfig.ensureDirectoryExists(GlobalConfig.getTestDir());
        String baseDocPath = GlobalConfig.getBaseDocPath();
        String suitePath = GlobalConfig.getSuiteBasePath() + testPath;
        
        TestExecutionManager manager = new TestExecutionManager(
            GlobalConfig.getJarPath(),
            resultDir, 
            baseDocPath, 
            suitePath
        );
        
        try {
            manager.runTestSuiteParallelExecution(Path.of("jdk/java/util/ArrayList"));
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 处理generate命令
     * 
     * @param testPath 测试路径
     */
    public static void handleGenerateCommand(String testPath) {
        File resultDir = GlobalConfig.ensureDirectoryExists(GlobalConfig.getTestDir());
        String baseDocPath = GlobalConfig.getBaseDocPath();
        String suitePath = GlobalConfig.getJdkTestPath() + "/jdk/" + testPath;
        
        TestExecutionManager manager = new TestExecutionManager(
            GlobalConfig.getJarPath(),
            resultDir, 
            baseDocPath, 
            suitePath
        );
        
        try {
            manager.runTestSuiteParallel();
            // 生成完成后验证Bug
            BugVerificationService.verifyBugs();
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 处理env命令 - 测试JDK环境
     */
    public static void handleEnvironmentCommand() {
        TestExecutor executor = new TestExecutor();
        try {
            executor.testJDKEnvironment();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 处理verify命令
     */
    public static void handleVerifyCommand() {
        BugVerificationService.verifyBugs();
    }
} 