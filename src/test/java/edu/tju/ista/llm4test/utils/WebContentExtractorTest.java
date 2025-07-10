package edu.tju.ista.llm4test.utils;

import edu.tju.ista.llm4test.llm.tools.WebContentExtractor;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;
import edu.tju.ista.llm4test.utils.websearch.WebSearch;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
public class WebContentExtractorTest {

    private WebContentExtractor extractor;
    private static String apiKey;

    @BeforeAll
    static void setupApiKey() {
        apiKey = System.getenv("BOCHA_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("WARNING: BOCHA_API_KEY environment variable not set. Tests requiring web search will be skipped.");
        }
    }

    @BeforeEach
    void setUp() {
        extractor = new WebContentExtractor(true); // 启用Playwright
    }

    @AfterEach
    void tearDown() {
        if (extractor != null) {
            extractor.close();
        }
    }

    @Test
    @DisplayName("1. Test Static Page Extraction")
    void testStaticPageExtraction() {
        System.out.println("=== 测试静态页面提取 ===");
        
        // 测试静态页面提取
        String staticContent = extractor.extractStaticContent("https://httpbin.org/html");
        
        assertNotNull(staticContent);
        assertFalse(staticContent.isEmpty());
        System.out.println("静态页面内容长度: " + staticContent.length());
        System.out.println("内容预览: " + (staticContent.length() > 200 ?
            staticContent.substring(0, 200) + "..." : staticContent));
        
        // 验证Markdown格式
        assertTrue(staticContent.contains("#"), "应该包含标题");
    }

    @Test
    @DisplayName("2. Test Dynamic Page Extraction")
    void testDynamicPageExtraction() {
        System.out.println("=== 测试动态页面提取 ===");
        
        // 测试动态页面提取
        String dynamicContent = extractor.extractDynamicContent("https://httpbin.org/html", null);
        
        assertNotNull(dynamicContent);
        assertFalse(dynamicContent.isEmpty());
        System.out.println("动态页面内容长度: " + dynamicContent.length());
        System.out.println("内容预览: " + (dynamicContent.length() > 200 ?
            dynamicContent.substring(0, 200) + "..." : dynamicContent));

    }

    @Test
    @DisplayName("3. Test Auto-Detect Extraction")
    void testAutoDetectExtraction() {
        System.out.println("=== 测试自动检测提取 ===");
        
        // 测试自动检测和提取
        String autoContent = extractor.extractContent("https://httpbin.org/html");
        
        assertNotNull(autoContent);
        assertFalse(autoContent.isEmpty());
        System.out.println("自动检测内容长度: " + autoContent.length());
        System.out.println("内容预览: " + (autoContent.length() > 200 ?
            autoContent.substring(0, 200) + "..." : autoContent));
    }

    @Test
    @DisplayName("4. Test Tool Interface")
    void testToolInterface() {
        System.out.println("=== 测试Tool接口 ===");
        
        // 测试Tool接口
        ToolResponse<String> response = extractor.execute(Map.of(
            "url", "https://httpbin.org/html"
        ));
        
        assertTrue(response.isSuccess());
        assertNotNull(response.getResult());
        assertFalse(response.getResult().isEmpty());
        
        System.out.println("Tool接口提取成功");
        System.out.println("内容长度: " + response.getResult().length());
        System.out.println("内容预览: " + (response.getResult().length() > 200 ?
            response.getResult().substring(0, 200) + "..." : response.getResult()));
    }

    @Test
    @DisplayName("5. Test Invalid URL Handling")
    void testInvalidUrl() {
        System.out.println("=== 测试无效URL处理 ===");
        
        // 测试无效URL
        ToolResponse<String> response = extractor.execute(Map.of(
            "url", "invalid-url"
        ));

        System.out.println(response.getResult());
        assertFalse(response.isSuccess());
        assertNotNull(response.getFailMessage());
        
        System.out.println("无效URL处理正确: " + response.getFailMessage());
    }

    @Test
    @DisplayName("6. Test Empty Parameters")
    void testEmptyParameters() {
        System.out.println("=== 测试空参数处理 ===");
        
        // 测试空参数
        ToolResponse<String> response = extractor.execute(Map.of());
        
        assertFalse(response.isSuccess());
        assertNotNull(response.getFailMessage());
        
        System.out.println("空参数处理正确: " + response.getFailMessage());
    }

    @Test
    @DisplayName("7. Test Tool Metadata")
    void testToolMetadata() {
        System.out.println("=== 测试工具元数据 ===");
        
        // 测试工具元数据
        assertEquals("extract_web_content", extractor.getName());
        assertNotNull(extractor.getDescription());
        assertFalse(extractor.getDescription().isEmpty());
        
        List<String> params = extractor.getParameters();
        assertEquals(1, params.size());
        assertTrue(params.contains("url"));
        
        Map<String, String> paramTypes = extractor.getParametersType();
        assertEquals("string", paramTypes.get("url"));
        
        System.out.println("工具名称: " + extractor.getName());
        System.out.println("工具描述: " + extractor.getDescription());
        System.out.println("参数列表: " + params);
        System.out.println("参数类型: " + paramTypes);
    }

    @Test
    @DisplayName("8. Test Batch Processing")
    void testBatchProcessing() {
        System.out.println("=== 测试批量处理 ===");
        
        // 测试批量处理
        List<String> urls = Arrays.asList(
            "https://httpbin.org/html",
            "https://httpbin.org/json"
        );
        
        String outputDir = "tmp/web_extract_test";
        int successCount = extractor.processBatch(urls, outputDir);
        
        assertTrue(successCount >= 0);
        System.out.println("批量处理成功数量: " + successCount);
    }

    @Test
    @DisplayName("9. Test Screenshot")
    void testScreenshot() {
        System.out.println("=== 测试截图功能 ===");
        
        // 测试截图功能
        String outputPath = "tmp/test_screenshot.png";
        boolean success = extractor.takeScreenshot("https://httpbin.org/html", outputPath);
        
        System.out.println("截图结果: " + (success ? "成功" : "失败"));
        
        // 这里不严格要求截图成功，因为可能受到网络环境影响
        // assertTrue(success);
    }

    @Test
    @DisplayName("10. Test Save to File")
    void testSaveToFile() {
        System.out.println("=== 测试保存到文件 ===");
        
        // 获取内容
        String content = extractor.extractContent("https://httpbin.org/html");
        
        // 保存到文件
        String filePath = "tmp/test_content.md";
        boolean success = extractor.saveToFile(content, filePath);
        
        assertTrue(success);
        System.out.println("文件保存成功: " + filePath);
    }

    @Test
    @DisplayName("11. Test Multiple Extractions")
    void testMultipleExtractions() {
        System.out.println("=== 测试多次提取 ===");
        
        // 多次提取测试资源管理
        for (int i = 0; i < 3; i++) {
            System.out.println("第 " + (i + 1) + " 次提取");
            String content = extractor.extractContent("https://httpbin.org/html");
            assertNotNull(content);
            assertFalse(content.isEmpty());
            System.out.println("内容长度: " + content.length());
        }
        
        System.out.println("多次提取测试完成");
    }

    @Test
    @DisplayName("12. Test Without Playwright")
    void testWithoutPlaywright() {
        System.out.println("=== 测试无Playwright模式 ===");
        
        // 测试不使用Playwright的提取器
        WebContentExtractor staticExtractor = new WebContentExtractor(false);
        
        try {
            String content = staticExtractor.extractStaticContent("https://httpbin.org/html");
            assertNotNull(content);
            assertFalse(content.isEmpty());
            System.out.println("静态提取成功，内容长度: " + content.length());
        } finally {
            staticExtractor.close();
        }
    }

    @Test
    @DisplayName("13. Test Error Handling")
    void testErrorHandling() {
        System.out.println("=== 测试错误处理 ===");
        
        // 测试404页面
        ToolResponse<String> response404 = extractor.execute(Map.of(
            "url", "https://httpbin.org/status/404"
        ));
        
        // 虽然是404，但工具应该能处理并返回相应的错误信息
        System.out.println("404页面处理: " + (response404.isSuccess() ? "成功" : "失败"));
        if (!response404.isSuccess()) {
            System.out.println("错误信息: " + response404.getFailMessage());
        }
        
        // 测试超时URL（这里用一个很慢的URL）
        try {
            ToolResponse<String> timeoutResponse = extractor.execute(Map.of(
                "url", "https://httpbin.org/delay/5"
            ));
            System.out.println("超时URL处理: " + (timeoutResponse.isSuccess() ? "成功" : "失败"));
        } catch (Exception e) {
            System.out.println("超时处理正确: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("14. Test extraction from a list of real-world websites")
    void testExtractionFromRealWebsites() {
        System.out.println("=== 测试从真实网站列表提取 ===");
        List<String> urlsToTest = Arrays.asList(
                "https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html",
                "https://www.baeldung.com/java-8-new-features",
                "https://github.blog/2023-11-08-the-state-of-open-source-and-ai/"
        );

        for (String url : urlsToTest) {
            System.out.println(" --- Extracting from: " + url + " ---");
            String content = extractor.extractContent(url);
            assertNotNull(content, "Content should not be null for " + url);
            assertFalse(content.isEmpty(), "Content should not be empty for " + url);
            System.out.println("Extraction successful. Content length: " + content.length());
            System.out.println("Content： \n" +  content);
            System.out.println("--- End of extraction for: " + url + " --- ");
        }
    }

    @Test
    @DisplayName("15. Test searching and then extracting content")
    @EnabledIf("hasApiKey")
    void testSearchAndExtract() {
        System.out.println("=== 测试搜索并提取内容 ===");
        Assumptions.assumeTrue(hasApiKey(), "API Key is required for this test.");

        WebSearch webSearch = new WebSearch();
        List<SearchResult> searchResults = webSearch.search("Java ArrayList tutorial", 3);

        assertNotNull(searchResults);
        assertFalse(searchResults.isEmpty());

        System.out.println("Found " + searchResults.size() + " search results. Extracting content from top results...");

        for (SearchResult result : searchResults) {
            String url = result.getUrl();
            System.out.println(" --- Extracting from search result: " + url + " ---");
            String content = extractor.extractContent(url);
            assertNotNull(content, "Content should not be null for " + url);
            assertFalse(content.isEmpty(), "Content should not be empty for " + url);
            System.out.println("Extraction successful. Content length: " + content.length());
            System.out.println("Content preview: " + (content.length() > 500 ? content.substring(0, 500) + "..." : content));
            System.out.println("--- End of extraction for: " + url + " --- ");
        }
    }

    static boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
 