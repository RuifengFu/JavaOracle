package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.agents.InformationCollectionAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.List;

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
            System.out.println("内容预览: " + (info.content.length() > 2000 ?
                info.content.substring(0, 2000) + "..." : info.content));
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
} 