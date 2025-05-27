package edu.tju.ista.llm4test.concurrent;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 性能监控器
 * 
 * 监控并发执行的性能指标，包括：
 * - 线程池状态
 * - LLM调用统计
 * - 系统资源使用情况
 * - 吞吐量统计
 */
public class PerformanceMonitor {
    
    private static volatile PerformanceMonitor instance;
    
    private final ScheduledExecutorService scheduler;
    private final ConcurrentExecutionManager concurrentManager;
    private final AsyncLLMClient llmClient;
    
    // 性能指标
    private final AtomicLong totalTasksProcessed = new AtomicLong(0);
    private final AtomicLong totalLLMCalls = new AtomicLong(0);
    private final AtomicLong totalTestExecutions = new AtomicLong(0);
    
    private volatile long startTime;
    private volatile boolean monitoring = false;
    
    private PerformanceMonitor() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "PerformanceMonitor");
            t.setDaemon(true);
            return t;
        });
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
        this.llmClient = AsyncLLMClient.getInstance();
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * 获取单例实例
     */
    public static PerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (PerformanceMonitor.class) {
                if (instance == null) {
                    instance = new PerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * 开始监控
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }
        
        monitoring = true;
        startTime = System.currentTimeMillis();
        
        // 每30秒输出一次性能报告
        scheduler.scheduleAtFixedRate(this::logPerformanceReport, 30, 30, TimeUnit.SECONDS);
        
        LoggerUtil.logExec(Level.INFO, "性能监控已启动");
    }
    
    /**
     * 停止监控
     */
    public void stopMonitoring() {
        if (!monitoring) {
            return;
        }
        
        monitoring = false;
        scheduler.shutdown();
        
        // 输出最终报告
        logFinalReport();
        
        LoggerUtil.logExec(Level.INFO, "性能监控已停止");
    }
    
    /**
     * 记录任务处理
     */
    public void recordTaskProcessed() {
        totalTasksProcessed.incrementAndGet();
    }
    
    /**
     * 记录LLM调用
     */
    public void recordLLMCall() {
        totalLLMCalls.incrementAndGet();
    }
    
    /**
     * 记录测试执行
     */
    public void recordTestExecution() {
        totalTestExecutions.incrementAndGet();
    }
    
    /**
     * 输出性能报告
     */
    private void logPerformanceReport() {
        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - startTime) / 1000;
        
        if (elapsedSeconds == 0) {
            return;
        }
        
        // 计算吞吐量
        double tasksPerSecond = (double) totalTasksProcessed.get() / elapsedSeconds;
        double llmCallsPerSecond = (double) totalLLMCalls.get() / elapsedSeconds;
        double testsPerSecond = (double) totalTestExecutions.get() / elapsedSeconds;
        
        // 获取系统信息
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1024 / 1024; // MB
        long freeMemory = runtime.freeMemory() / 1024 / 1024;   // MB
        long usedMemory = totalMemory - freeMemory;
        
        StringBuilder report = new StringBuilder();
        report.append("\n=== 性能监控报告 ===\n");
        report.append(String.format("运行时间: %d秒\n", elapsedSeconds));
        report.append(String.format("总任务数: %d (%.2f/秒)\n", totalTasksProcessed.get(), tasksPerSecond));
        report.append(String.format("LLM调用: %d (%.2f/秒)\n", totalLLMCalls.get(), llmCallsPerSecond));
        report.append(String.format("测试执行: %d (%.2f/秒)\n", totalTestExecutions.get(), testsPerSecond));
        report.append(String.format("内存使用: %dMB / %dMB (%.1f%%)\n", 
            usedMemory, totalMemory, (double) usedMemory / totalMemory * 100));
        
        // 线程池状态
        report.append("\n--- 线程池状态 ---\n");
        report.append(concurrentManager.getLLMPoolStatus()).append("\n");
        report.append(concurrentManager.getTestPoolStatus()).append("\n");
        
        // LLM客户端统计
        report.append("\n--- LLM客户端统计 ---\n");
        report.append(llmClient.getStatistics()).append("\n");
        
        report.append("==================\n");
        
        LoggerUtil.logExec(Level.INFO, report.toString());
    }
    
    /**
     * 输出最终报告
     */
    private void logFinalReport() {
        long currentTime = System.currentTimeMillis();
        long totalElapsedSeconds = (currentTime - startTime) / 1000;
        
        StringBuilder finalReport = new StringBuilder();
        finalReport.append("\n=== 最终性能报告 ===\n");
        finalReport.append(String.format("总运行时间: %d秒 (%.2f分钟)\n", 
            totalElapsedSeconds, totalElapsedSeconds / 60.0));
        finalReport.append(String.format("总任务处理数: %d\n", totalTasksProcessed.get()));
        finalReport.append(String.format("总LLM调用数: %d\n", totalLLMCalls.get()));
        finalReport.append(String.format("总测试执行数: %d\n", totalTestExecutions.get()));
        
        if (totalElapsedSeconds > 0) {
            finalReport.append(String.format("平均任务处理速度: %.2f任务/秒\n", 
                (double) totalTasksProcessed.get() / totalElapsedSeconds));
            finalReport.append(String.format("平均LLM调用速度: %.2f调用/秒\n", 
                (double) totalLLMCalls.get() / totalElapsedSeconds));
            finalReport.append(String.format("平均测试执行速度: %.2f测试/秒\n", 
                (double) totalTestExecutions.get() / totalElapsedSeconds));
        }
        
        finalReport.append("\n--- 最终线程池状态 ---\n");
        finalReport.append(concurrentManager.getLLMPoolStatus()).append("\n");
        finalReport.append(concurrentManager.getTestPoolStatus()).append("\n");
        
        finalReport.append("\n--- 最终LLM统计 ---\n");
        finalReport.append(llmClient.getStatistics()).append("\n");
        
        finalReport.append("====================\n");
        
        LoggerUtil.logExec(Level.INFO, finalReport.toString());
    }
    
    /**
     * 获取当前性能快照
     */
    public String getPerformanceSnapshot() {
        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - startTime) / 1000;
        
        if (elapsedSeconds == 0) {
            return "性能监控刚启动，暂无数据";
        }
        
        return String.format(
            "运行时间: %ds | 任务: %d (%.1f/s) | LLM: %d (%.1f/s) | 测试: %d (%.1f/s)",
            elapsedSeconds,
            totalTasksProcessed.get(), (double) totalTasksProcessed.get() / elapsedSeconds,
            totalLLMCalls.get(), (double) totalLLMCalls.get() / elapsedSeconds,
            totalTestExecutions.get(), (double) totalTestExecutions.get() / elapsedSeconds
        );
    }
} 