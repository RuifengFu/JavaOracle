package edu.tju.ista.llm4test.concurrent;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * 异步LLM客户端
 * 
 * 提供异步LLM调用、连接池管理、重试机制、请求去重等功能
 */
public class AsyncLLMClient {
    
    private static volatile AsyncLLMClient instance;
    
    // LLM客户端连接池
    private final BlockingQueue<OpenAI> r1ClientPool;
    private final BlockingQueue<OpenAI> jsonClientPool;
    private final BlockingQueue<OpenAI> doubaoClientPool;
    
    // 连接池大小
    private final int poolSize;
    
    // 请求缓存（去重）
    private final ConcurrentHashMap<String, CompletableFuture<String>> requestCache;
    private final ConcurrentHashMap<String, CompletableFuture<List<ToolCall>>> funcCallCache;
    
    // 统计信息
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    
    // 并发管理器
    private final ConcurrentExecutionManager concurrentManager;
    
    private AsyncLLMClient() {
        this.poolSize = Math.max(5, Runtime.getRuntime().availableProcessors());
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
        
        // 初始化连接池
        this.r1ClientPool = new LinkedBlockingQueue<>();
        this.jsonClientPool = new LinkedBlockingQueue<>();
        this.doubaoClientPool = new LinkedBlockingQueue<>();
        
        // 初始化缓存
        this.requestCache = new ConcurrentHashMap<>();
        this.funcCallCache = new ConcurrentHashMap<>();
        
        // 预创建连接
        initializeConnectionPools();
        
        LoggerUtil.logExec(Level.INFO, 
            String.format("异步LLM客户端初始化完成 - 连接池大小: %d", poolSize));
    }
    
    /**
     * 获取单例实例
     */
    public static AsyncLLMClient getInstance() {
        if (instance == null) {
            synchronized (AsyncLLMClient.class) {
                if (instance == null) {
                    instance = new AsyncLLMClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化连接池
     */
    private void initializeConnectionPools() {
        // 创建R1客户端池
        for (int i = 0; i < poolSize; i++) {
            r1ClientPool.offer(new OpenAI());
        }
        
        // 创建JSON客户端池
        for (int i = 0; i < poolSize; i++) {
            OpenAI jsonClient = new OpenAI();
            // 这里需要设置JSON输出模式，但OpenAI类的字段是private
            // 可能需要添加setter方法或者构造函数参数
            jsonClientPool.offer(jsonClient);
        }
        
        // 创建Doubao客户端池
        for (int i = 0; i < poolSize; i++) {
            doubaoClientPool.offer(OpenAI.DoubaoFlash);
        }
    }
    
    /**
     * 异步消息完成调用（R1模型）
     */
    public CompletableFuture<String> messageCompletionAsync(String prompt) {
        return messageCompletionAsync(prompt, ClientType.R1);
    }
    
    /**
     * 异步消息完成调用（指定客户端类型）
     */
    public CompletableFuture<String> messageCompletionAsync(String prompt, ClientType clientType) {
        totalRequests.incrementAndGet();
        
        // 检查缓存
        String cacheKey = generateCacheKey(prompt, clientType.name());
        CompletableFuture<String> cachedResult = requestCache.get(cacheKey);
        if (cachedResult != null) {
            cacheHits.incrementAndGet();
            LoggerUtil.logExec(Level.FINE, "LLM请求命中缓存: " + cacheKey.substring(0, Math.min(50, cacheKey.length())));
            return cachedResult;
        }
        
        // 创建新的异步请求
        CompletableFuture<String> future = concurrentManager.submitLLMTask(() -> {
            return executeWithRetry(() -> callMessageCompletion(prompt, clientType), 3);
        });
        
        // 缓存结果
        requestCache.put(cacheKey, future);
        
        // 清理缓存（异步）
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // 出错时移除缓存
                requestCache.remove(cacheKey);
            } else {
                // 成功时设置过期清理（简单实现：延迟清理）
                concurrentManager.submitBatchTask(() -> {
                    try {
                        Thread.sleep(300_000); // 5分钟后清理
                        requestCache.remove(cacheKey);
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                });
            }
        });
        
        return future;
    }
    
    /**
     * 异步函数调用
     */
    public CompletableFuture<List<ToolCall>> funcCallAsync(String prompt, List<Tool<?>> tools) {
        return funcCallAsync(prompt, tools, ClientType.DOUBAO);
    }
    
    /**
     * 异步函数调用（指定客户端类型）
     */
    public CompletableFuture<List<ToolCall>> funcCallAsync(String prompt, List<Tool<?>> tools, ClientType clientType) {
        totalRequests.incrementAndGet();
        
        // 检查缓存
        String cacheKey = generateCacheKey(prompt + tools.toString(), "FUNC_" + clientType.name());
        var cachedResult = funcCallCache.get(cacheKey);
        if (cachedResult != null) {
            cacheHits.incrementAndGet();
            return cachedResult;
        }
        
        // 创建新的异步请求
        var future = concurrentManager.submitLLMTask(() -> {
            return executeWithRetry(() -> callFuncCall(prompt, tools, clientType), 3);
        });
        
        // 缓存结果
        funcCallCache.put(cacheKey, future);
        
        // 清理缓存
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                funcCallCache.remove(cacheKey);
            } else {
                concurrentManager.submitBatchTask(() -> {
                    try {
                        Thread.sleep(300_000); // 5分钟后清理
                        funcCallCache.remove(cacheKey);
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                });
            }
        });
        
        return future;
    }
    
    /**
     * 从连接池获取客户端并执行消息完成调用
     */
    private String callMessageCompletion(String prompt, ClientType clientType) throws Exception {
        BlockingQueue<OpenAI> pool = getClientPool(clientType);
        OpenAI client = pool.take(); // 阻塞获取连接
        
        try {
            return client.messageCompletion(prompt);
        } finally {
            pool.offer(client); // 归还连接
        }
    }
    
    /**
     * 从连接池获取客户端并执行函数调用
     */
    private List<ToolCall> callFuncCall(String prompt, List<Tool<?>> tools, ClientType clientType) throws Exception {
        BlockingQueue<OpenAI> pool = getClientPool(clientType);
        OpenAI client = pool.take(); // 阻塞获取连接
        
        try {
            return client.toolCall(prompt, tools);
        } finally {
            pool.offer(client); // 归还连接
        }
    }
    
    /**
     * 根据客户端类型获取对应的连接池
     */
    private BlockingQueue<OpenAI> getClientPool(ClientType clientType) {
        return switch (clientType) {
            case R1 -> r1ClientPool;
            case JSON -> jsonClientPool;
            case DOUBAO -> doubaoClientPool;
        };
    }
    
    /**
     * 带重试的执行
     */
    private <T> T executeWithRetry(Callable<T> task, int maxRetries) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    retryCount.incrementAndGet();
                    LoggerUtil.logExec(Level.WARNING, 
                        String.format("LLM调用失败，第%d次重试: %s", attempt + 1, e.getMessage()));
                    
                    // 指数退避
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("LLM调用在" + (maxRetries + 1) + "次尝试后仍然失败", lastException);
    }
    
    /**
     * 生成缓存键
     */
    private String generateCacheKey(String content, String type) {
        return type + ":" + content.hashCode();
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        double hitRate = totalRequests.get() > 0 ? 
            (double) cacheHits.get() / totalRequests.get() * 100 : 0;
            
        return String.format(
            "LLM客户端统计 - 总请求: %d, 缓存命中: %d (%.1f%%), 重试次数: %d", 
            totalRequests.get(), cacheHits.get(), hitRate, retryCount.get());
    }
    
    /**
     * 清理缓存
     */
    public void clearCache() {
        requestCache.clear();
        funcCallCache.clear();
        LoggerUtil.logExec(Level.INFO, "LLM请求缓存已清理");
    }
    
    /**
     * 客户端类型枚举
     */
    public enum ClientType {
        R1,      // 标准客户端
        JSON,    // JSON输出客户端
        DOUBAO   // Doubao客户端
    }
} 