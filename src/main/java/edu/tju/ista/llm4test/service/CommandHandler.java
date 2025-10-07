package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestSuite;
import edu.tju.ista.llm4test.utils.JavaBaseClassCollector;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
            manager.runTestSuiteParallelExecution();
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

    /**
     * 处理getClass命令
     */
    public static void handleGetClassCommand(String testPath) {
        String suitePath = GlobalConfig.getSuiteBasePath() + testPath;
        TestSuite suite = new TestSuite(suitePath);
        JavaBaseClassCollector collector = new JavaBaseClassCollector();

        List<File> files = suite.getTestFiles().stream()
                .filter(suite::isValidTestFile)
                .collect(Collectors.toList());

        Set<String> classNames = collector.collectClasses(files);
        Path output = Paths.get("class.txt").toAbsolutePath();
        try {
            collector.writeToFile(classNames, output);
            System.out.println("已收集 " + classNames.size() + " 个 java.base 类，输出文件: " + output);
        } catch (Exception e) {
            throw new RuntimeException("写入class.txt失败", e);
        }
    }
} 
