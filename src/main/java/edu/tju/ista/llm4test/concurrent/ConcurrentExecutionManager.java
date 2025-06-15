package edu.tju.ista.llm4test.concurrent;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 并发执行管理器
 * 
 * 使用现代化 executor 架构：
 * - LLM调用：虚拟线程（轻量化，适合IO密集型）
 * - 测试执行：ForkJoinPool（适合CPU密集型）
 * - 批量处理：虚拟线程（后台低优先级任务）
 */
public class ConcurrentExecutionManager {
    
    private static volatile ConcurrentExecutionManager instance;
    
    // IO密集型线程池（LLM调用）- 使用虚拟线程
    private final ExecutorService llmExecutor;
    
    // CPU密集型线程池（测试执行）- 使用 ForkJoinPool
    private final ExecutorService testExecutor;
    
    // 批量处理线程池 - 使用虚拟线程
    private final ExecutorService batchExecutor;
    
    // 监控指标
    private final AtomicInteger activeLLMTasks = new AtomicInteger(0);
    private final AtomicInteger activeTestTasks = new AtomicInteger(0);
    private final AtomicInteger activeBatchTasks = new AtomicInteger(0);
    
    // 是否支持虚拟线程
    private final boolean virtualThreadsSupported;
    
    private ConcurrentExecutionManager() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
//        this.virtualThreadsSupported = isVirtualThreadsSupported();
        this.virtualThreadsSupported = false;
        
        // LLM线程池：优先使用虚拟线程，轻量化处理IO密集型任务
        this.llmExecutor = createLLMExecutor();
        
        // 测试执行线程池：使用 ForkJoinPool，专为CPU密集型任务优化
        this.testExecutor = new ForkJoinPool(
            cpuCores * 3 / 2,                                    // 并行度 = CPU核心数 * 1.5
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            new UncaughtExceptionHandler(),              // 异常处理
            false                                        // 非异步模式
        );
        
        // 批量处理线程池：使用虚拟线程或轻量级线程池
        this.batchExecutor = createBatchExecutor();
        
        LoggerUtil.logExec(Level.INFO, 
            String.format("并发执行管理器初始化完成 - CPU核心数: %d, 虚拟线程支持: %s, LLM执行器: %s, 测试执行器: ForkJoinPool", 
                cpuCores, virtualThreadsSupported, 
                virtualThreadsSupported ? "虚拟线程" : "传统线程池"));
        
        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }
    
    /**
     * 检查是否支持虚拟线程（Java 19+）
     */
    private boolean isVirtualThreadsSupported() {
        try {
            // 尝试获取虚拟线程相关的方法
            Thread.class.getMethod("startVirtualThread", Runnable.class);
            return true;
        } catch (NoSuchMethodException e) {
            LoggerUtil.logExec(Level.INFO, "当前Java版本不支持虚拟线程，将使用传统线程池");
            return false;
        }
    }
    
    /**
     * 创建LLM执行器
     */
    private ExecutorService createLLMExecutor() {
        if (virtualThreadsSupported) {
            try {
                // 使用虚拟线程执行器，非常适合IO密集型的LLM调用
                return (ExecutorService) Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor")
                    .invoke(null);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "创建虚拟线程执行器失败，使用传统线程池: " + e.getMessage());
            }
        }
        
        // 降级到传统线程池
        int cpuCores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            Math.min(50, cpuCores * 2),      // 核心线程数，不超过50
            50,                              // 最大线程数固定为50
            60L, TimeUnit.SECONDS,           // 空闲超时
            new LinkedBlockingQueue<>(1000), // 更大的队列以容纳等待的任务
            new NamedThreadFactory("LLM-Worker"),
            new ThreadPoolExecutor.CallerRunsPolicy() // 背压策略
        );
    }
    
    /**
     * 创建批量处理执行器
     */
    private ExecutorService createBatchExecutor() {
//        if (virtualThreadsSupported) {
//            try {
//                // 为批量处理使用虚拟线程，轻量级且适合后台任务
//                return (ExecutorService) Executors.class
//                    .getMethod("newVirtualThreadPerTaskExecutor")
//                    .invoke(null);
//            } catch (Exception e) {
//                LoggerUtil.logExec(Level.WARNING, "创建虚拟线程批量执行器失败，使用传统线程池: " + e.getMessage());
//            }
//        }
        
        // 降级到传统线程池
        return new ThreadPoolExecutor(
            2,                               // 核心线程数
            6,                               // 最大线程数
            120L, TimeUnit.SECONDS,          // 空闲超时
            new LinkedBlockingQueue<>(100),  // 有界队列
            new NamedThreadFactory("Batch-Processor"),
            new ThreadPoolExecutor.DiscardOldestPolicy() // 丢弃最旧任务
        );
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
     * 提交LLM任务（IO密集型）- 使用虚拟线程优化
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
     * 提交测试执行任务（CPU密集型）- 使用ForkJoinPool优化
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
        activeBatchTasks.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException("批量任务执行失败", e);
            } finally {
                activeBatchTasks.decrementAndGet();
            }
        }, batchExecutor);
    }
    
    /**
     * 获取LLM线程池状态
     */
    public String getLLMPoolStatus() {
        if (virtualThreadsSupported) {
            return String.format("LLM虚拟线程池 - 活跃任务: %d", activeLLMTasks.get());
        } else if (llmExecutor instanceof ThreadPoolExecutor tpe) {
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
        if (testExecutor instanceof ForkJoinPool fjp) {
            return String.format("测试ForkJoinPool - 活跃: %d, 并行度: %d, 窃取: %d, 队列: %d", 
                fjp.getActiveThreadCount(), fjp.getParallelism(), 
                fjp.getStealCount(), fjp.getQueuedSubmissionCount());
        }
        return "测试线程池状态不可用";
    }
    
    /**
     * 获取批量处理状态
     */
    public String getBatchPoolStatus() {
        if (virtualThreadsSupported) {
            return String.format("批量虚拟线程池 - 活跃任务: %d", activeBatchTasks.get());
        } else if (batchExecutor instanceof ThreadPoolExecutor tpe) {
            return String.format("批量线程池 - 活跃: %d/%d, 队列: %d", 
                tpe.getActiveCount(), tpe.getPoolSize(), tpe.getQueue().size());
        }
        return "批量线程池状态不可用";
    }
    
    /**
     * 获取总体状态
     */
    public void logStatus() {
        LoggerUtil.logExec(Level.INFO, 
            String.format("并发执行状态 - %s | %s | %s | 虚拟线程支持: %s", 
                getLLMPoolStatus(), getTestPoolStatus(), getBatchPoolStatus(),
                virtualThreadsSupported));
    }
    
    /**
     * 检查系统负载并提供优化建议
     */
    public void performanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 性能报告 ===\n");
        report.append(String.format("虚拟线程支持: %s\n", virtualThreadsSupported));
        report.append(String.format("CPU核心数: %d\n", Runtime.getRuntime().availableProcessors()));
        report.append(String.format("当前活跃LLM任务: %d\n", activeLLMTasks.get()));
        report.append(String.format("当前活跃测试任务: %d\n", activeTestTasks.get()));
        report.append(String.format("当前活跃批量任务: %d\n", activeBatchTasks.get()));
        
        // 性能建议
        if (!virtualThreadsSupported) {
            report.append("建议: 升级到Java 19+以获得虚拟线程支持，可显著提升IO密集型任务性能\n");
        }
        
        LoggerUtil.logExec(Level.INFO, report.toString());
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
            LoggerUtil.logExec(Level.INFO, "关闭" + name + "执行器...");
            executor.shutdown();
            
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                LoggerUtil.logExec(Level.WARNING, name + "执行器在" + timeoutSeconds + "秒内未完成，强制关闭");
                executor.shutdownNow();
                
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LoggerUtil.logExec(Level.SEVERE, name + "执行器无法正常关闭");
                }
            } else {
                LoggerUtil.logExec(Level.INFO, name + "执行器关闭成功");
            }
        } catch (InterruptedException e) {
            LoggerUtil.logExec(Level.WARNING, "关闭" + name + "执行器时被中断");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 未捕获异常处理器
     */
    private static class UncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            LoggerUtil.logExec(Level.SEVERE, 
                String.format("线程 %s 发生未捕获异常: %s - %s", t.getName(), e.getClass().getSimpleName(), e.getMessage()));
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
            // t.setDaemon(true); // 设置为守护线程
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
} 