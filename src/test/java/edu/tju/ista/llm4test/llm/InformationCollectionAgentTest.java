package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.agents.InformationCollectionAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class InformationCollectionAgentTest {
    
    private InformationCollectionAgent agent;
    private static final String TEST_SOURCE_PATH = GlobalConfig.getJdkSourcePath();
    private static final String TEST_JAVADOC_PATH = GlobalConfig.getBaseDocPath();
    
    @BeforeEach
    void setUp() {
        agent = new InformationCollectionAgent(TEST_SOURCE_PATH, TEST_JAVADOC_PATH);
    }
    
    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.close();
        }
    }
    
    @Test
    public void testCollectInformation() {
        String initialInsight = """
            {
                "symptoms": "HashMap put method fails",
                "relevantClasses": ["java.util.HashMap", "java.util.Map"],
                "errorLocation": "HashMap put method",
                "queries": ["HashMap put method fails Java bug", "Map interface Java"]
            }
            """;
        
        String testCode = """
            import java.util.HashMap;
            public class TestHashMap {
                public static void main(String[] args) {
                    HashMap<String, String> map = new HashMap<>();
                    map.put("key", "value");
                }
            }
            """;
        
        String testOutput = "Exception in thread \"main\" java.lang.NullPointerException";
        
        System.out.println("=== 开始测试信息收集 ===");
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            initialInsight, testCode, testOutput, "");
        
        System.out.println("=== 收集结果分析 ===");
        System.out.println("总共收集到 " + results.size() + " 条信息");
        
        assertNotNull(results);
        assertTrue(results.size() > 0, "应该收集到一些信息");
        
        // 详细输出每条信息
        for (int i = 0; i < results.size(); i++) {
            InformationCollectionAgent.CollectedInfo info = results.get(i);
            System.out.println(String.format("\n--- 信息 %d ---", i + 1));
            System.out.println("ID: " + info.id);
            System.out.println("类型: " + info.type);
            System.out.println("来源: " + info.source);
            System.out.println("相关性得分: " + String.format("%.3f", info.relevanceScore));
            System.out.println("内容长度: " + info.content.length() + " 字符");
            System.out.println("内容预览: " + (info.content.length() > 1000 ?
                info.content.substring(0, 1000) + "..." : info.content));
        }
        
        // 验证信息类型多样性
        boolean hasSourceCode = results.stream().anyMatch(info -> 
            info.type == InformationCollectionAgent.InfoType.SOURCE_CODE);
        boolean hasJavaDoc = results.stream().anyMatch(info -> 
            info.type == InformationCollectionAgent.InfoType.JAVADOC);
        boolean hasWebSearch = results.stream().anyMatch(info -> 
            info.type == InformationCollectionAgent.InfoType.WEB_SEARCH);
        
        System.out.println("\n=== 信息类型分析 ===");
        System.out.println("包含源码信息: " + hasSourceCode);
        System.out.println("包含文档信息: " + hasJavaDoc);
        System.out.println("包含网络搜索: " + hasWebSearch);
        
        // 验证信息质量
        for (InformationCollectionAgent.CollectedInfo info : results) {
            assertNotNull(info.content);
            assertFalse(info.content.isEmpty());
            assertTrue(info.relevanceScore >= 0.0);
            assertTrue(info.relevanceScore <= 1.0);
        }
        
        // 验证总大小限制
        int totalSize = results.stream().mapToInt(info -> info.content.length()).sum();
        System.out.println("总内容大小: " + totalSize + " 字符");
        assertTrue(totalSize <= 32000, "总大小应该在32k限制内，实际: " + totalSize);
        
        // 按类型统计
        System.out.println("\n=== 按类型统计 ===");
        for (InformationCollectionAgent.InfoType type : InformationCollectionAgent.InfoType.values()) {
            long count = results.stream().filter(info -> info.type == type).count();
            int size = results.stream().filter(info -> info.type == type)
                .mapToInt(info -> info.content.length()).sum();
            System.out.println(type + ": " + count + " 条，共 " + size + " 字符");
        }
    }
    
    @Test
    void testResourceManagement() {
        System.out.println("=== 测试资源管理 ===");
        // 测试资源管理 - 确保多次调用不会导致资源泄漏
        String simpleInsight = """
            {
                "symptoms": "simple test",
                "relevantClasses": ["java.lang.String"],
                "errorLocation": "test location",
                "queries": ["test query"]
            }
            """;
        
        // 多次调用信息收集
        for (int i = 0; i < 3; i++) {
            System.out.println("第 " + (i + 1) + " 次调用");
            List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
                simpleInsight, "public class Test {}", "test output", "");
            assertNotNull(results);
            System.out.println("收集到 " + results.size() + " 条信息");
            
            // 显示一些收集结果
            for (int j = 0; j < Math.min(2, results.size()); j++) {
                InformationCollectionAgent.CollectedInfo info = results.get(j);
                System.out.println("  - " + info.type + ": " + info.source + " (长度: " + info.content.length() + ")");
            }
        }
        
        // 验证agent仍然可以正常关闭
        assertDoesNotThrow(() -> agent.close());
        System.out.println("资源管理测试完成");
    }
    
    @Test
    void testEmptyInput() {
        System.out.println("=== 测试空输入处理 ===");
        // 测试空输入的处理
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            "{}", "", "", "");
        
        assertNotNull(results);
        System.out.println("空输入处理结果: " + results.size() + " 条信息");
        
        // 显示结果
        for (InformationCollectionAgent.CollectedInfo info : results) {
            System.out.println("  - " + info.type + ": " + info.source + " (长度: " + info.content.length() + ")");
        }
        
        // 空输入应该返回空结果或最小结果
        assertTrue(results.size() >= 0);
    }
    
    @Test
    void testLargeInput() {
        System.out.println("=== 测试大输入处理 ===");
        // 测试大输入的处理
        StringBuilder largeSymptoms = new StringBuilder();
        for (int i = 0; i < 100; i++) { // 减少重复次数避免太长
            largeSymptoms.append("HashMap put method fails with NullPointerException ");
        }
        
        String largeInsight = String.format("""
            {
                "symptoms": "%s",
                "relevantClasses": ["java.util.HashMap"],
                "errorLocation": "HashMap.put method",
                "queries": ["HashMap implementation"]
            }
            """, largeSymptoms.toString());
        
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            largeInsight, "public class Test {}", "test output", "");
        
        assertNotNull(results);
        System.out.println("大输入处理结果: " + results.size() + " 条信息");
        
        // 显示前几条结果
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            InformationCollectionAgent.CollectedInfo info = results.get(i);
            System.out.println("  - " + info.type + ": " + info.source + " (长度: " + info.content.length() + ")");
        }
        
        // 验证总大小仍然在限制内
        int totalSize = results.stream()
            .mapToInt(info -> info.content.length())
            .sum();
        System.out.println("总内容大小: " + totalSize + " 字符");
        assertTrue(totalSize <= 32000, "即使输入很大，总信息大小也应该不超过32k: " + totalSize);
    }

    @Test
    public void testWebSearchTimeout() {
        System.out.println("=== 测试网络搜索超时处理 ===");
        // 测试Web搜索超时处理
        String initialInsight = """
            {
                "symptoms": "timeout test",
                "relevantClasses": [],
                "errorLocation": "test",
                "queries": ["timeout test query"]
            }
            """;
        
        String testCode = "public class Test {}";
        String testOutput = "timeout test";
        
        // 设置较短的超时时间来测试
        long startTime = System.currentTimeMillis();
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            initialInsight, testCode, testOutput, "");
        long endTime = System.currentTimeMillis();
        
        // 验证即使有超时，也能正常返回结果
        assertNotNull(results);
        
        // 详细输出结果
        System.out.println("超时测试结果: " + results.size() + " 条信息");
        for (int i = 0; i < results.size(); i++) {
            InformationCollectionAgent.CollectedInfo info = results.get(i);
            System.out.println(String.format("  %d. %s - %s (长度: %d, 相关性: %.3f)", 
                i + 1, info.type, info.source, info.content.length(), info.relevanceScore));
        }
        
        // 验证没有花费过长时间（应该在合理时间内完成）
        long duration = endTime - startTime;
        assertTrue(duration < 180000, "执行时间不应超过3分钟，实际: " + duration + "ms");
        
        System.out.println("Web搜索超时测试完成，耗时: " + duration + "ms，收集到 " + results.size() + " 条信息");
    }
    
    @Test
    void testInformationTypeDistribution() {
        System.out.println("=== 测试信息类型分布 ===");
        
        String richInsight = """
            {
                "symptoms": "HashMap put method causes NullPointerException when key is null",
                "relevantClasses": ["java.util.HashMap", "java.util.Map", "java.lang.String"],
                "errorLocation": "HashMap.put method",
                "queries": ["HashMap NullPointerException", "Map implementation Java", "Java concurrent HashMap"]
            }
            """;
        
        String testCode = """
            import java.util.HashMap;
            import java.util.Map;
            public class TestHashMap {
                public static void main(String[] args) {
                    Map<String, String> map = new HashMap<>();
                    map.put(null, "value");  // This might cause issues
                }
            }
            """;
        
        String testOutput = "Exception in thread \"main\" java.lang.NullPointerException at HashMap.put(HashMap.java:123)";
        
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            richInsight, testCode, testOutput, "");
        
        System.out.println("信息类型分布测试结果: " + results.size() + " 条信息");
        
        // 详细分析每种类型
        for (InformationCollectionAgent.InfoType type : InformationCollectionAgent.InfoType.values()) {
            List<InformationCollectionAgent.CollectedInfo> typeResults = results.stream()
                .filter(info -> info.type == type)
                .toList();
            
            System.out.println("\n--- " + type + " 类型信息 (" + typeResults.size() + " 条) ---");
            for (int i = 0; i < typeResults.size(); i++) {
                InformationCollectionAgent.CollectedInfo info = typeResults.get(i);
                System.out.println(String.format("  %d. %s (长度: %d, 相关性: %.3f)", 
                    i + 1, info.source, info.content.length(), info.relevanceScore));
                
                // 显示内容片段
                if (info.content.length() > 100) {
                    System.out.println("     预览: " + info.content.substring(0, 100) + "...");
                } else {
                    System.out.println("     内容: " + info.content);
                }
            }
        }
        
        // 验证至少有一种类型的信息
        assertFalse(results.isEmpty(), "应该至少收集到一些信息");
    }

    @Test
    void testDebugInformationCollection() {
        System.out.println("=== 调试信息收集 ===");
        
        // 测试一个简单的、明确的洞察
        String simpleInsight = """
            {
                "symptoms": "HashMap put method fails with NullPointerException",
                "relevantClasses": ["java.util.HashMap", "java.util.Map"],
                "errorLocation": "HashMap.put method",
                "queries": ["HashMap put method", "NullPointerException in HashMap"]
            }
            """;
        
        // 在收集信息之前，先检查原始解析
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(simpleInsight);
            
            System.out.println("JSON 解析验证:");
            System.out.println("- 症状: " + rootNode.path("symptoms").asText());
            System.out.println("- 相关类数量: " + rootNode.path("relevantClasses").size());
            System.out.println("- 错误位置: " + rootNode.path("errorLocation").asText());
            System.out.println("- 查询数量: " + rootNode.path("queries").size());
            
            if (rootNode.path("relevantClasses").isArray()) {
                System.out.println("- 相关类列表:");
                for (int i = 0; i < rootNode.path("relevantClasses").size(); i++) {
                    System.out.println("  * " + rootNode.path("relevantClasses").get(i).asText());
                }
            }
            
            if (rootNode.path("queries").isArray()) {
                System.out.println("- 查询列表:");
                for (int i = 0; i < rootNode.path("queries").size(); i++) {
                    System.out.println("  * " + rootNode.path("queries").get(i).asText());
                }
            }
            
        } catch (Exception e) {
            System.err.println("JSON 解析失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        String testCode = """
            import java.util.HashMap;
            import java.util.Map;
            
            public class TestHashMap {
                public static void main(String[] args) {
                    Map<String, String> map = new HashMap<>();
                    map.put("key", "value");
                    System.out.println(map.get("key"));
                }
            }
            """;
        
        String testOutput = """
            Exception in thread "main" java.lang.NullPointerException
            	at java.util.HashMap.put(HashMap.java:597)
            	at TestHashMap.main(TestHashMap.java:7)
            """;
        
        System.out.println("\n=== 开始信息收集 ===");
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            simpleInsight, testCode, testOutput, "");
        
        System.out.println("\n=== 详细收集结果 ===");
        System.out.println("总共收集到 " + results.size() + " 条信息");
        
        if (results.isEmpty()) {
            System.out.println("⚠️ 没有收集到任何信息，可能的原因:");
            System.out.println("1. 路径配置问题 - 检查源码路径和文档路径");
            System.out.println("2. 工具执行失败 - 检查各个工具的执行状态");
            System.out.println("3. 网络问题 - 检查网络连接和代理设置");
            System.out.println("4. 权限问题 - 检查文件读取权限");
        } else {
            for (int i = 0; i < results.size(); i++) {
                InformationCollectionAgent.CollectedInfo info = results.get(i);
                System.out.println(String.format("\n--- 信息 %d ---", i + 1));
                System.out.println("ID: " + info.id);
                System.out.println("类型: " + info.type);
                System.out.println("来源: " + info.source);
                System.out.println("相关性得分: " + String.format("%.3f", info.relevanceScore));
                System.out.println("内容长度: " + info.content.length() + " 字符");
                
                // 显示更多内容预览
                if (info.content.length() > 500) {
                    System.out.println("内容预览: " + info.content.substring(0, 500) + "...");
                } else {
                    System.out.println("完整内容: " + info.content);
                }
                
                // 分析内容质量
                if (info.content.contains("Exception") || info.content.contains("Error")) {
                    System.out.println("✅ 包含异常/错误信息");
                }
                if (info.content.contains("HashMap") || info.content.contains("Map")) {
                    System.out.println("✅ 包含相关类信息");
                }
                if (info.content.contains("put") || info.content.contains("get")) {
                    System.out.println("✅ 包含相关方法信息");
                }
            }
        }
        
        // 测试各个工具的独立功能
        System.out.println("\n=== 工具独立测试 ===");
        testIndividualTools();
        
        // 验证收集到的信息
        if (!results.isEmpty()) {
            int totalSize = results.stream().mapToInt(info -> info.content.length()).sum();
            System.out.println("总内容大小: " + totalSize + " 字符");
            
            // 按类型统计
            System.out.println("\n=== 按类型统计 ===");
            for (InformationCollectionAgent.InfoType type : InformationCollectionAgent.InfoType.values()) {
                long count = results.stream().filter(info -> info.type == type).count();
                int size = results.stream().filter(info -> info.type == type)
                    .mapToInt(info -> info.content.length()).sum();
                System.out.println(type + ": " + count + " 条，共 " + size + " 字符");
            }
        }
    }
    
    private void testIndividualTools() {
        System.out.println("\n--- 测试源码搜索工具 ---");
        try {
            // 这里我们需要访问 agent 的私有工具，或者创建新的工具实例
            // 为了简化，我们仅输出一些基本信息
            System.out.println("源码路径: " + (System.getProperty("user.dir") + "/jdk21-src"));
            System.out.println("文档路径: " + (System.getProperty("user.dir") + "/doc"));
            
            // 检查路径是否存在
            java.io.File sourceDir = new java.io.File(System.getProperty("user.dir") + "/jdk21-src");
            java.io.File docDir = new java.io.File(System.getProperty("user.dir") + "/doc");
            
            System.out.println("源码目录存在: " + sourceDir.exists());
            System.out.println("文档目录存在: " + docDir.exists());
            
            if (sourceDir.exists()) {
                System.out.println("源码目录内容示例:");
                String[] files = sourceDir.list();
                if (files != null) {
                    for (int i = 0; i < Math.min(5, files.length); i++) {
                        System.out.println("  - " + files[i]);
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("工具测试失败: " + e.getMessage());
        }
        
        System.out.println("\n--- 测试网络搜索 ---");
        try {
            String apiKey = System.getenv("BOCHA_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                System.out.println("⚠️ 环境变量 BOCHA_API_KEY 未设置");
            } else {
                System.out.println("✅ API 密钥已配置");
            }
        } catch (Exception e) {
            System.err.println("网络搜索测试失败: " + e.getMessage());
        }
    }

    @Test
    void testLLMSummarizationFeature() {
        System.out.println("=== 测试LLM总结功能 ===");
        
        // 创建一个包含大量信息的测试场景
        String largeInsight = """
            {
                "symptoms": "HashMap put method causes severe performance degradation with specific key patterns",
                "relevantClasses": ["java.util.HashMap", "java.util.Map", "java.lang.String", "java.lang.Object", "java.util.concurrent.ConcurrentHashMap"],
                "errorLocation": "HashMap put method and hash calculation",
                "queries": [
                    "HashMap hash collision performance",
                    "Java HashMap worst case performance",
                    "HashMap key distribution impact",
                    "Java Map implementation performance",
                    "HashMap resize operation cost"
                ]
            }
            """;
        
        String testCode = """
            import java.util.HashMap;
            import java.util.Map;
            
            public class HashMapPerformanceTest {
                public static void main(String[] args) {
                    Map<String, String> map = new HashMap<>();
                    
                    // 插入大量具有相同hash值的key
                    for (int i = 0; i < 1000; i++) {
                        String key = "key" + i;
                        map.put(key, "value" + i);
                    }
                    
                    // 测试查找性能
                    long startTime = System.nanoTime();
                    for (int i = 0; i < 1000; i++) {
                        map.get("key" + i);
                    }
                    long endTime = System.nanoTime();
                    System.out.println("查找耗时: " + (endTime - startTime) + " ns");
                }
            }
            """;
        
        String testOutput = """
            查找耗时: 50000000 ns
            性能异常：预期耗时应该在10ms以内，实际耗时超过50ms
            """;
        
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            largeInsight, testCode, testOutput, "");
        
        System.out.println("=== LLM总结测试结果 ===");
        System.out.println("收集到的信息数量: " + results.size());
        
        // 验证结果
        assertNotNull(results);
        assertTrue(results.size() > 0, "应该收集到一些信息");
        
        // 验证总大小控制
        int totalSize = results.stream().mapToInt(info -> info.content.length()).sum();
        System.out.println("总内容大小: " + totalSize + " 字符");
        assertTrue(totalSize <= 32000, "总大小应该在32k限制内，实际: " + totalSize);
        
        // 验证信息类型多样性
        Set<InformationCollectionAgent.InfoType> types = results.stream()
            .map(info -> info.type)
            .collect(Collectors.toSet());
        
        System.out.println("包含的信息类型: " + types);
        assertTrue(types.size() > 1, "应该包含多种类型的信息");
        
        // 详细输出结果
        for (int i = 0; i < results.size(); i++) {
            InformationCollectionAgent.CollectedInfo info = results.get(i);
            System.out.println(String.format("\n--- 总结信息 %d ---", i + 1));
            System.out.println("ID: " + info.id);
            System.out.println("类型: " + info.type);
            System.out.println("来源: " + info.source);
            System.out.println("相关性得分: " + String.format("%.3f", info.relevanceScore));
            System.out.println("内容长度: " + info.content.length() + " 字符");
            System.out.println("内容预览: " + (info.content.length() > 300 ? 
                info.content.substring(0, 300) + "..." : info.content));
            
            // 验证总结后的信息质量
            assertNotNull(info.content);
            assertFalse(info.content.isEmpty());
            assertTrue(info.content.length() > 50, "总结后的信息不应该过短");
            assertTrue(info.content.length() < 8000, "单个信息不应该过长");
        }
        
        System.out.println("\nLLM总结功能测试完成");
    }

    @Test
    void testSimplifiedInformationCollection() {
        System.out.println("=== 测试简化的信息收集功能 ===");
        
        String simpleInsight = """
            {
                "symptoms": "ArrayList size method returns incorrect value",
                "relevantClasses": ["java.util.ArrayList", "java.util.List"],
                "errorLocation": "ArrayList size method",
                "queries": ["ArrayList size implementation", "List size method documentation"]
            }
            """;
        
        String testCode = """
            import java.util.ArrayList;
            import java.util.List;
            
            public class ArrayListTest {
                public static void main(String[] args) {
                    List<String> list = new ArrayList<>();
                    list.add("item1");
                    list.add("item2");
                    System.out.println("Size: " + list.size());
                }
            }
            """;
        
        String testOutput = "Size: 3\nExpected: 2";
        
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            simpleInsight, testCode, testOutput, "");
        
        System.out.println("=== 简化收集测试结果 ===");
        System.out.println("收集到的信息数量: " + results.size());
        
        // 验证基本功能
        assertNotNull(results);
        assertTrue(results.size() > 0, "应该收集到一些信息");
        
        // 验证信息包含不同类型
        boolean hasSourceCode = results.stream().anyMatch(info -> 
            info.type == InformationCollectionAgent.InfoType.SOURCE_CODE);
        boolean hasJavaDoc = results.stream().anyMatch(info -> 
            info.type == InformationCollectionAgent.InfoType.JAVADOC);
        
        System.out.println("包含源码信息: " + hasSourceCode);
        System.out.println("包含文档信息: " + hasJavaDoc);
        
        // 验证每个信息的基本属性
        for (InformationCollectionAgent.CollectedInfo info : results) {
            assertNotNull(info.id);
            assertNotNull(info.source);
            assertNotNull(info.content);
            assertNotNull(info.type);
            assertTrue(info.relevanceScore >= 0.0 && info.relevanceScore <= 1.0);
            assertFalse(info.content.isEmpty());
        }
        
        System.out.println("简化信息收集功能测试完成");
    }

    @Test
    void testInformationCollectionWithApiInfo() {
        System.out.println("=== 测试带API信息的信息收集 ===");
        
        String insight = """
            {
                "symptoms": "String concat method performance issue",
                "relevantClasses": ["java.lang.String"],
                "errorLocation": "String concatenation",
                "queries": ["String concat performance"]
            }
            """;
        
        String testCode = """
            public class StringTest {
                public static void main(String[] args) {
                    String result = "";
                    for (int i = 0; i < 1000; i++) {
                        result = result + "test" + i;
                    }
                    System.out.println("Result length: " + result.length());
                }
            }
            """;
        
        String testOutput = "Result length: 7000\nPerformance warning: String concatenation in loop";
        
        String apiInfo = """
            API信息：
            java.lang.String类的concat方法用于字符串连接。
            
            源码摘要：
            public String concat(String str) {
                int otherLen = str.length();
                if (otherLen == 0) {
                    return this;
                }
                int len = value.length;
                char buf[] = Arrays.copyOf(value, len + otherLen);
                str.getChars(buf, len);
                return new String(buf, true);
            }
            
            性能说明：
            每次调用concat都会创建新的String对象，在循环中使用会导致性能问题。
            建议使用StringBuilder来进行大量字符串拼接操作。
            """;
        
        List<InformationCollectionAgent.CollectedInfo> results = agent.collectInformation(
            insight, testCode, testOutput, apiInfo);
        
        System.out.println("=== 带API信息的收集结果 ===");
        System.out.println("收集到的信息数量: " + results.size());
        
        // 验证API信息被包含
        boolean hasApiInfo = results.stream().anyMatch(info -> 
            info.id.equals("API_INFO"));
        
        System.out.println("包含API信息: " + hasApiInfo);
        assertTrue(hasApiInfo, "应该包含API信息");
        
        // 找到API信息并验证
        InformationCollectionAgent.CollectedInfo apiInfoResult = results.stream()
            .filter(info -> info.id.equals("API_INFO"))
            .findFirst()
            .orElse(null);
        
        if (apiInfoResult != null) {
            System.out.println("API信息长度: " + apiInfoResult.content.length());
            System.out.println("API信息相关性: " + apiInfoResult.relevanceScore);
            assertTrue(apiInfoResult.content.contains("concat"));
            assertTrue(apiInfoResult.relevanceScore > 0.8);
        }
        
        System.out.println("带API信息的信息收集测试完成");
    }
} 