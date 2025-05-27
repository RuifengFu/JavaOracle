package edu.tju.ista.llm4test.concurrent;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 并发执行管理器
 * 
 * 分离IO密集型任务（LLM调用）和CPU密集型任务（测试执行）的线程池
 * 实现智能资源调度和负载均衡
 */
public class ConcurrentExecutionManager {
    
    private static volatile ConcurrentExecutionManager instance;
    
    // IO密集型线程池（LLM调用）
    private final ExecutorService llmExecutor;
    
    // CPU密集型线程池（测试执行）
    private final ExecutorService testExecutor;
    
    // 批量处理线程池
    private final ExecutorService batchExecutor;
    
    // 监控指标
    private final AtomicInteger activeLLMTasks = new AtomicInteger(0);
    private final AtomicInteger activeTestTasks = new AtomicInteger(0);
    
    private ConcurrentExecutionManager() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        
        // LLM线程池：IO密集，可以设置较多线程，避免等待时阻塞
        this.llmExecutor = new ThreadPoolExecutor(
            cpuCores * 2,                    // 核心线程数
            cpuCores * 4,                    // 最大线程数  
            60L, TimeUnit.SECONDS,           // 空闲超时
            new LinkedBlockingQueue<>(200),  // 有界队列，避免内存溢出
            new NamedThreadFactory("LLM-Worker"),
            new ThreadPoolExecutor.CallerRunsPolicy() // 背压策略
        );
        
        // 测试执行线程池：CPU密集，线程数接近CPU核心数
        this.testExecutor = new ThreadPoolExecutor(
            cpuCores,                        // 核心线程数 = CPU核心数
            cpuCores + 2,                    // 最大线程数，稍微多一点处理突发
            30L, TimeUnit.SECONDS,           // 空闲超时
            new LinkedBlockingQueue<>(100),  // 有界队列
            new NamedThreadFactory("Test-Executor"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 批量处理线程池：低优先级后台任务
        this.batchExecutor = new ThreadPoolExecutor(
            2,                               // 核心线程数
            4,                               // 最大线程数
            120L, TimeUnit.SECONDS,          // 空闲超时
            new LinkedBlockingQueue<>(50),   // 有界队列
            new NamedThreadFactory("Batch-Processor"),
            new ThreadPoolExecutor.DiscardOldestPolicy() // 丢弃最旧任务
        );
        
        LoggerUtil.logExec(Level.INFO, 
            String.format("并发执行管理器初始化完成 - CPU核心数: %d, LLM线程池: %d-%d, 测试线程池: %d-%d", 
                cpuCores, cpuCores * 2, cpuCores * 4, cpuCores, cpuCores + 2));
        
        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }
    
    /**
     * 获取单例实例
     */
    public static ConcurrentExecutionManager getInstance() {
        if (instance == null) {
            synchronized (ConcurrentExecutionManager.class) {
                if (instance == null) {
                    instance = new ConcurrentExecutionManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 提交LLM任务（IO密集型）
     */
    public <T> CompletableFuture<T> submitLLMTask(Callable<T> task) {
        activeLLMTasks.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException("LLM任务执行失败", e);
            } finally {
                activeLLMTasks.decrementAndGet();
            }
        }, llmExecutor);
    }
    
    /**
     * 提交LLM任务（Runnable版本）
     */
    public CompletableFuture<Void> submitLLMTask(Runnable task) {
        activeLLMTasks.incrementAndGet();
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } finally {
                activeLLMTasks.decrementAndGet();
            }
        }, llmExecutor);
    }
    
    /**
     * 提交测试执行任务（CPU密集型）
     */
    public <T> CompletableFuture<T> submitTestTask(Callable<T> task) {
        activeTestTasks.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException("测试任务执行失败", e);
            } finally {
                activeTestTasks.decrementAndGet();
            }
        }, testExecutor);
    }
    
    /**
     * 提交测试执行任务（Runnable版本）
     */
    public CompletableFuture<Void> submitTestTask(Runnable task) {
        activeTestTasks.incrementAndGet();
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } finally {
                activeTestTasks.decrementAndGet();
            }
        }, testExecutor);
    }
    
    /**
     * 提交批量处理任务
     */
    public <T> CompletableFuture<T> submitBatchTask(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException("批量任务执行失败", e);
            }
        }, batchExecutor);
    }
    
    /**
     * 获取LLM线程池状态
     */
    public String getLLMPoolStatus() {
        if (llmExecutor instanceof ThreadPoolExecutor tpe) {
            return String.format("LLM线程池 - 活跃: %d/%d, 队列: %d, 完成: %d", 
                tpe.getActiveCount(), tpe.getPoolSize(), 
                tpe.getQueue().size(), tpe.getCompletedTaskCount());
        }
        return "LLM线程池状态不可用";
    }
    
    /**
     * 获取测试线程池状态
     */
    public String getTestPoolStatus() {
        if (testExecutor instanceof ThreadPoolExecutor tpe) {
            return String.format("测试线程池 - 活跃: %d/%d, 队列: %d, 完成: %d", 
                tpe.getActiveCount(), tpe.getPoolSize(), 
                tpe.getQueue().size(), tpe.getCompletedTaskCount());
        }
        return "测试线程池状态不可用";
    }
    
    /**
     * 获取总体状态
     */
    public void logStatus() {
        LoggerUtil.logExec(Level.INFO, 
            String.format("并发执行状态 - %s | %s | 活跃LLM任务: %d, 活跃测试任务: %d", 
                getLLMPoolStatus(), getTestPoolStatus(), 
                activeLLMTasks.get(), activeTestTasks.get()));
    }
    
    /**
     * 优雅关闭所有线程池
     */
    public void shutdown() {
        LoggerUtil.logExec(Level.INFO, "开始关闭并发执行管理器...");
        
        shutdownExecutorGracefully(llmExecutor, "LLM", 30);
        shutdownExecutorGracefully(testExecutor, "测试", 60);
        shutdownExecutorGracefully(batchExecutor, "批量", 10);
        
        LoggerUtil.logExec(Level.INFO, "并发执行管理器关闭完成");
    }
    
    /**
     * 优雅关闭单个线程池
     */
    private void shutdownExecutorGracefully(ExecutorService executor, String name, int timeoutSeconds) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        
        try {
            LoggerUtil.logExec(Level.INFO, "关闭" + name + "线程池...");
            executor.shutdown();
            
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                LoggerUtil.logExec(Level.WARNING, name + "线程池在" + timeoutSeconds + "秒内未完成，强制关闭");
                executor.shutdownNow();
                
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LoggerUtil.logExec(Level.SEVERE, name + "线程池无法正常关闭");
                }
            } else {
                LoggerUtil.logExec(Level.INFO, name + "线程池关闭成功");
            }
        } catch (InterruptedException e) {
            LoggerUtil.logExec(Level.WARNING, "关闭" + name + "线程池时被中断");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 命名线程工厂
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        
        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix + "-";
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true); // 设置为守护线程
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
} 