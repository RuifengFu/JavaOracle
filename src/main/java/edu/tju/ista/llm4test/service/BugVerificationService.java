package edu.tju.ista.llm4test.service;

import edu.tju.ista.llm4test.config.ApplicationConfig;
import edu.tju.ista.llm4test.llm.agents.BugVerifyAgent;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

/**
 * Bug验证服务
 * 负责处理Bug验证相关的功能
 */
public class BugVerificationService {

    /**
     * 验证已识别的bug并生成详细报告
     * 
     * @param logPath 用于读取验证结果的日志文件路径
     */
    public static void verifyBugs(String logPath) {
        LoggerUtil.logResult(Level.INFO, "开始验证bug并生成报告...");
        LoggerUtil.logResult(Level.INFO, "使用日志文件: " + logPath);
        
        // 设置路径
        String baseDocPath = ApplicationConfig.getBaseDocPath();
        String jdkSourcePath = ApplicationConfig.getJdkSourcePath();
        
        // 创建Bug报告目录
        String bugReportPath = createBugReportDirectory();
        
        // 调用BugVerifyAgent的verifyBugsFromLog方法
        BugVerifyAgent.verifyBugsFromLog(logPath, baseDocPath, jdkSourcePath, bugReportPath);
    }

    /**
     * 使用默认日志路径验证Bug
     */
    public static void verifyBugs() {
        verifyBugs(ApplicationConfig.getLogFile());
    }
    
    /**
     * 创建Bug报告目录
     * 
     * @return Bug报告目录路径
     */
    private static String createBugReportDirectory() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String bugReportPath = ApplicationConfig.getBugReportDir() + "/" + timestamp;
        
        File bugReportDir = new File(bugReportPath);
        if (!bugReportDir.exists()) {
            bugReportDir.mkdirs();
            LoggerUtil.logResult(Level.INFO, "创建Bug报告目录: " + bugReportDir.getAbsolutePath());
        }
        
        return bugReportPath;
    }
} 