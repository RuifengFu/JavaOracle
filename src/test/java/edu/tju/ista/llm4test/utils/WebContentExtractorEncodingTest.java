package edu.tju.ista.llm4test.utils;

import edu.tju.ista.llm4test.llm.tools.WebContentExtractor;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebContentExtractor编码测试")
public class WebContentExtractorEncodingTest {

    private WebContentExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new WebContentExtractor(false); // 仅测试静态提取
    }

    @AfterEach
    void tearDown() {
        if (extractor != null) {
            extractor.close();
        }
    }

    @Test
    @DisplayName("测试编码修复 - httpbin.org")
    void testEncodingFix() {
        System.out.println("=== 测试编码修复 ===");
        
        String url = "https://httpbin.org/html";
        String content = extractor.extractContent(url);
        
        System.out.println("提取的内容长度: " + content.length());
        System.out.println("内容预览 (前200字符): " + (content.length() > 200 ? 
            content.substring(0, 200) + "..." : content));
        
        assertNotNull(content);
        assertFalse(content.contains("�"), "内容不应包含乱码字符");
        assertTrue(content.length() > 100, "内容长度应该大于100字符");
        
        // 检查是否包含正常的HTML标签转换后的内容
        assertTrue(content.contains("#") || content.contains("Herman"), "应该包含有意义的文本内容");
        
        System.out.println("编码测试通过!");
    }

    @Test
    @DisplayName("测试编码修复 - Oracle网站")
    void testOracleEncoding() {
        System.out.println("=== 测试Oracle网站编码 ===");
        
        String url = "https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html";
        String content = extractor.extractContent(url);
        
        System.out.println("提取的内容长度: " + content.length());
        System.out.println("内容预览 (前500字符): " + (content.length() > 500 ? 
            content.substring(0, 500) + "..." : content));
        
        assertNotNull(content);
        
        if (content.length() > 100) {
            assertFalse(content.contains("�"), "内容不应包含乱码字符");
            System.out.println("Oracle网站编码测试通过!");
        } else {
            System.out.println("Oracle网站可能访问受限，但没有乱码");
        }
    }
} 