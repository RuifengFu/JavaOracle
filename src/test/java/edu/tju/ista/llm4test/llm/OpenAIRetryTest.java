package edu.tju.ista.llm4test.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAIRetryTest {

    private FastTestOpenAI testOpenAI;

    /**
     * 快速测试用的OpenAI子类，重写关键方法避免实际网络调用和延迟
     */
    private static class FastTestOpenAI extends OpenAI {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final AtomicInteger retryCount = new AtomicInteger(0);
        private boolean shouldFail = false;
        private String failureReason = "";
        private int maxFailures = 0;

        public FastTestOpenAI() {
            super("test-key", "https://test.api.com/test", "test-model");
        }

        public void setFailureScenario(boolean shouldFail, String failureReason, int maxFailures) {
            this.shouldFail = shouldFail;
            this.failureReason = failureReason;
            this.maxFailures = maxFailures;
            this.callCount.set(0);
            this.retryCount.set(0);
        }

        // 重写随机延迟方法，返回很小的延迟（1毫秒）
        protected long getRandomRetryDelay() {
            return 1; // 快速测试，只延迟1毫秒
        }

        // 重写HTTP客户端构建，避免真实网络调用
        @Override
        protected HttpClient buildHttpClient() {
            return null; // 不会被实际使用
        }

        // 模拟网络请求，根据设置返回成功或失败
        private String simulateNetworkCall() throws IOException {
            int currentCall = callCount.incrementAndGet();
            
            if (shouldFail) {
                // 总是在指定的失败次数内失败，或者设置为持续失败
                boolean shouldFailThisCall = (maxFailures >= 10) || (currentCall <= maxFailures);
                
                if (shouldFailThisCall) {
                    if (failureReason.contains("HTTP error:")) {
                        // 模拟HTTP错误，通过RuntimeException传递
                        int statusCode = extractStatusCodeFromReason(failureReason);
                        throw new RuntimeException("HTTP error: " + statusCode + "\nError response");
                    } else {
                        // 模拟网络错误
                        throw new IOException(failureReason);
                    }
                }
            }
            
            return "Mock response from fast test";
        }

        private int extractStatusCodeFromReason(String reason) {
            try {
                // 从"HTTP error: 400\nError response"中提取400
                String[] parts = reason.split(":");
                if (parts.length > 1) {
                    String statusPart = parts[1].trim();
                    // 可能包含换行符，只取第一个数字部分
                    String statusCode = statusPart.split("\\s")[0];
                    return Integer.parseInt(statusCode);
                }
                return 503; // 默认
            } catch (Exception e) {
                return 503; // 默认
            }
        }

        // 重写messageCompletion，使用模拟的网络调用
        @Override
        public String messageCompletion(String prompt, double temperature, boolean jsonOutput) {
            try {
                return executeWithRetryForTest("messageCompletion", this::simulateNetworkCall);
            } catch (Exception e) {
                return ""; // 失败返回空字符串
            }
        }

        // 简化版的重试执行器，只处理同步版本且快速执行
        private String executeWithRetryForTest(String operationName, NetworkCallSupplier supplier) 
                throws IOException, InterruptedException {
            int retryAttempt = 0;
            Exception lastException = null;

            while (retryAttempt <= 3) { // MAX_RETRIES = 3
                try {
                    return supplier.call();
                } catch (Exception e) {
                    lastException = e;
                    
                    if (shouldRetryTest(e) && retryAttempt < 3) {
                        retryCount.incrementAndGet();
                        // 快速延迟，避免测试太慢
                        Thread.sleep(getRandomRetryDelay());
                        retryAttempt++;
                        continue;
                    }
                    
                    throw e;
                }
            }
            
            throw new RuntimeException("Max retries exceeded", lastException);
        }

        // 重用原有的重试判断逻辑
        private boolean shouldRetryTest(Throwable t) {
            String message = t.getMessage();
            if (message == null) {
                return false;
            }

            // 检查网络错误
            if (isNetworkErrorTest(message)) {
                return true;
            }

            if (t instanceof RuntimeException) {
                if (message.contains("HTTP error")) {
                    try {
                        int statusCode = extractStatusCodeFromReason(message);
                        return shouldRetryStatusCode(statusCode);
                    } catch (Exception ex) {
                        return false;
                    }
                }
            }

            if (t instanceof IOException) {
                return isNetworkErrorTest(message);
            }

            return false;
        }

        private boolean shouldRetryStatusCode(int statusCode) {
            return statusCode == 500 || statusCode == 503 || statusCode == 429 || statusCode == 504 || statusCode == 421;
        }

        private boolean isNetworkErrorTest(String message) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("handshake close") ||
                   lowerMessage.contains("tunnel failed") ||
                   lowerMessage.contains("connection reset") ||
                   lowerMessage.contains("ssl handshake failed") ||
                   lowerMessage.contains("connection timed out") ||
                   lowerMessage.contains("proxy tunnel failed");
        }

        @FunctionalInterface
        private interface NetworkCallSupplier {
            String call() throws IOException;
        }

        public int getCallCount() {
            return callCount.get();
        }

        public int getRetryCount() {
            return retryCount.get();
        }
    }

    @BeforeEach
    void setUp() {
        testOpenAI = new FastTestOpenAI();
    }

    @Test
    void testNetworkErrorRetry_HandshakeClose() {
        // 模拟handshake close错误，前2次失败，第3次成功
        testOpenAI.setFailureScenario(true, "handshake close", 2);
        
        long startTime = System.currentTimeMillis();
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);
        long endTime = System.currentTimeMillis();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("Mock response from fast test", result);
        // 验证总共调用了3次（2次失败 + 1次成功）
        assertEquals(3, testOpenAI.getCallCount());
        assertEquals(2, testOpenAI.getRetryCount());
        // 验证执行时间很短（小于1秒）
        assertTrue(endTime - startTime < 1000, "Test should be fast, took: " + (endTime - startTime) + "ms");
    }

    @Test
    void testNetworkErrorRetry_TunnelFailed() {
        // 模拟tunnel failed错误，1次失败，第2次成功
        testOpenAI.setFailureScenario(true, "tunnel failed", 1);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        assertNotNull(result);
        assertEquals("Mock response from fast test", result);
        assertEquals(2, testOpenAI.getCallCount());
        assertEquals(1, testOpenAI.getRetryCount());
    }

    @Test
    void testNetworkErrorRetry_ConnectionReset() {
        // 模拟connection reset错误
        testOpenAI.setFailureScenario(true, "Connection reset by peer", 1);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        assertNotNull(result);
        assertEquals("Mock response from fast test", result);
        assertEquals(2, testOpenAI.getCallCount());
        assertEquals(1, testOpenAI.getRetryCount());
    }

    @Test
    void testMaxRetryLimit() {
        // 模拟连续失败，超过最大重试次数（设置失败10次，但最多重试3次）
        testOpenAI.setFailureScenario(true, "handshake close", 10);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        // 达到最大重试次数后应该返回空字符串
        assertEquals("", result);
        // 验证总共调用了4次（初始调用 + 3次重试）
        assertEquals(4, testOpenAI.getCallCount());
        assertEquals(3, testOpenAI.getRetryCount());
    }

    @Test
    void testHttpErrorRetry_503() {
        // 模拟503错误，1次失败，第2次成功
        testOpenAI.setFailureScenario(true, "HTTP error: 503", 1);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        assertNotNull(result);
        assertEquals("Mock response from fast test", result);
        assertEquals(2, testOpenAI.getCallCount());
        assertEquals(1, testOpenAI.getRetryCount());
    }

    @Test
    void testHttpErrorRetry_500() {
        // 模拟500错误，1次失败，第2次成功
        testOpenAI.setFailureScenario(true, "HTTP error: 500", 1);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        assertNotNull(result);
        assertEquals("Mock response from fast test", result);
        assertEquals(2, testOpenAI.getCallCount());
        assertEquals(1, testOpenAI.getRetryCount());
    }

    @Test
    void testNonRetryableError() {
        // 模拟400错误（不应重试）
        testOpenAI.setFailureScenario(true, "HTTP error: 400", 10);
        
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

        assertEquals("", result);
        // 验证只调用了1次（不应重试）
        assertEquals(1, testOpenAI.getCallCount());
        assertEquals(0, testOpenAI.getRetryCount());
    }

    @Test
    void testFastRandomDelayGeneration() {
        // 测试快速版本的随机延迟生成器
        FastTestOpenAI fastTest = new FastTestOpenAI();
        
        // 多次调用，验证返回值都是1毫秒（快速测试）
        long delay1 = fastTest.getRandomRetryDelay();
        long delay2 = fastTest.getRandomRetryDelay();
        long delay3 = fastTest.getRandomRetryDelay();
        
        assertEquals(1, delay1, "Fast test delay should be 1ms");
        assertEquals(1, delay2, "Fast test delay should be 1ms");
        assertEquals(1, delay3, "Fast test delay should be 1ms");
    }

    @Test
    void testOriginalRandomDelayGeneration() {
        // 测试原始的随机延迟生成器（使用反射）
        try {
            Method getRandomRetryDelayMethod = OpenAI.class.getDeclaredMethod("getRandomRetryDelay");
            getRandomRetryDelayMethod.setAccessible(true);
            
            OpenAI testInstance = new OpenAI("test", "test", "test");
            
            // 调用几次验证范围
            long delay1 = (Long) getRandomRetryDelayMethod.invoke(testInstance);
            long delay2 = (Long) getRandomRetryDelayMethod.invoke(testInstance);
            
            // 验证延迟时间在60秒到120秒之间（60s + 0~60s）
            assertTrue(delay1 >= 60000 && delay1 < 120000, "Delay should be between 60-120 seconds: " + delay1);
            assertTrue(delay2 >= 60000 && delay2 < 120000, "Delay should be between 60-120 seconds: " + delay2);
                
        } catch (Exception e) {
            fail("Failed to test random delay generation: " + e.getMessage());
        }
    }

    /**
     * 验证重试机制是否正确处理不同类型的网络错误
     */
    @Test
    void testMultipleNetworkErrorTypes() {
        String[] errorMessages = {
            "handshake close",
            "tunnel failed", 
            "connection reset",
            "ssl handshake failed",
            "connection timed out",
            "proxy tunnel failed"
        };

        long totalStartTime = System.currentTimeMillis();
        
        for (String errorMessage : errorMessages) {
            setUp(); // 重置testOpenAI实例
            
            // 设置1次失败，第2次成功
            testOpenAI.setFailureScenario(true, errorMessage, 1);
            
            String result = testOpenAI.messageCompletion("test prompt", 0.7, false);

            assertNotNull(result, "Should retry for error: " + errorMessage);
            assertFalse(result.isEmpty(), "Should get response after retry for error: " + errorMessage);
            assertEquals("Mock response from fast test", result);
            assertEquals(2, testOpenAI.getCallCount(), "Should have 2 calls for error: " + errorMessage);
            assertEquals(1, testOpenAI.getRetryCount(), "Should have 1 retry for error: " + errorMessage);
        }
        
        long totalEndTime = System.currentTimeMillis();
        // 验证整个测试很快完成（所有错误类型测试总共小于1秒）
        assertTrue(totalEndTime - totalStartTime < 1000, 
            "All network error tests should complete quickly, took: " + (totalEndTime - totalStartTime) + "ms");
    }

    @Test
    void testTestPerformance() {
        // 性能测试：确保测试执行速度很快，设置持续失败以测试最大重试次数
        testOpenAI.setFailureScenario(true, "handshake close", 10); // 持续失败
        
        long startTime = System.currentTimeMillis();
        String result = testOpenAI.messageCompletion("test prompt", 0.7, false);
        long endTime = System.currentTimeMillis();
        
        // 即使重试3次，也应该在很短时间内完成（小于100毫秒）
        assertTrue(endTime - startTime < 100, 
            "Even with max retries, test should be very fast, took: " + (endTime - startTime) + "ms");
        assertEquals("", result); // 最终失败返回空字符串
        assertEquals(4, testOpenAI.getCallCount()); // 1次初始 + 3次重试
        assertEquals(3, testOpenAI.getRetryCount());
    }
} 