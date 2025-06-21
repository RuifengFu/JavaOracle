package edu.tju.ista.llm4test.utils;

import edu.tju.ista.llm4test.utils.WebSearch;
import edu.tju.ista.llm4test.utils.WebSearch.SearchResult;
import edu.tju.ista.llm4test.utils.WebSearch.ImageResult;
import edu.tju.ista.llm4test.utils.WebSearch.Freshness;
import edu.tju.ista.llm4test.utils.WebSearch.SearchConfig;
import edu.tju.ista.llm4test.utils.WebSearch.SearchSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("WebSearch 博查AI API 测试")
public class WebSearchTest {
    
    private static String apiKey;
    private WebSearch webSearch;
    private SearchConfig config;
    
    @BeforeAll
    static void setupApiKey() {
        // 从环境变量获取API Key
        apiKey = System.getenv("BOCHA_API_KEY");
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("警告: 未设置API Key环境变量 BOCHA_API_KEY");
            System.out.println("网络相关的测试将被跳过，仅执行基本功能测试");
        } else {
            System.out.println("检测到API Key，将执行完整的网络测试");
        }
    }
    
    @BeforeEach
    void setUp() {
        // 设置基本配置
        config = new SearchConfig()
                .setApiKey(apiKey)
                .setFreshness(Freshness.NO_LIMIT)
                .setSummary(true)
                .setCount(5)
                .setTimeout(30)
                .setEnableLogging(true);
        
        webSearch = new WebSearch(config);
    }
    
    @Test
    @DisplayName("1. 基本对象创建测试")
    void testBasicObjectCreation() {
        // 测试SearchResult对象创建
        SearchResult result = new SearchResult("测试标题", "测试摘要", "http://example.com", 1);
        
        assertNotNull(result);
        assertEquals("测试标题", result.getName());
        assertEquals("测试标题", result.getTitle()); // 测试兼容性方法
        assertEquals("测试摘要", result.getSnippet());
        assertEquals("http://example.com", result.getUrl());
        assertEquals(1, result.getRank());
        
        System.out.println("✓ SearchResult对象创建成功: " + result);
    }
    
    @Test
    @DisplayName("2. 搜索配置测试")
    void testSearchConfig() {
        SearchConfig testConfig = new SearchConfig()
                .setApiKey("test-api-key")
                .setFreshness(Freshness.ONE_WEEK)
                .setSummary(true)
                .setInclude("example.com|test.com")
                .setExclude("spam.com")
                .setCount(20)
                .setTimeout(45)
                .setEnableLogging(false);
        
        assertNotNull(testConfig);
        assertEquals("test-api-key", testConfig.getApiKey());
        assertEquals(Freshness.ONE_WEEK, testConfig.getFreshness());
        assertTrue(testConfig.isSummary());
        assertEquals("example.com|test.com", testConfig.getInclude());
        assertEquals("spam.com", testConfig.getExclude());
        assertEquals(20, testConfig.getCount());
        assertEquals(45, testConfig.getTimeout());
        assertFalse(testConfig.isEnableLogging());
        
        System.out.println("✓ SearchConfig配置测试通过");
        System.out.println("  - 时间范围: " + testConfig.getFreshness().getValue());
        System.out.println("  - 启用摘要: " + testConfig.isSummary());
        System.out.println("  - 包含网站: " + testConfig.getInclude());
        System.out.println("  - 排除网站: " + testConfig.getExclude());
    }
    
    @Test
    @DisplayName("3. 时间范围枚举测试")
    void testFreshnessEnum() {
        assertEquals("noLimit", Freshness.NO_LIMIT.getValue());
        assertEquals("oneDay", Freshness.ONE_DAY.getValue());
        assertEquals("oneWeek", Freshness.ONE_WEEK.getValue());
        assertEquals("oneMonth", Freshness.ONE_MONTH.getValue());
        assertEquals("oneYear", Freshness.ONE_YEAR.getValue());
        
        System.out.println("✓ 时间范围枚举测试通过");
        for (Freshness freshness : Freshness.values()) {
            System.out.println("  - " + freshness.name() + ": " + freshness.getValue());
        }
    }
    
    @Test
    @DisplayName("4. API Key验证测试")
    void testApiKeyValidation() {
        // 测试没有API Key的情况
        SearchConfig noKeyConfig = new SearchConfig();
        WebSearch noKeySearch = new WebSearch(noKeyConfig);
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            noKeySearch.search("test query");
        });
        
        assertTrue(exception.getMessage().contains("API Key不能为空"));
        System.out.println("✓ API Key验证测试通过: " + exception.getMessage());
    }
    
    @Test
    @DisplayName("5. 基本搜索测试")
    @EnabledIf("hasApiKey")
    void testBasicSearch() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        List<SearchResult> results = webSearch.search("JTreg Java测试框架");
        
        assertNotNull(results);
        assertFalse(results.isEmpty(), "搜索结果不应为空");
        assertTrue(results.size() <= 5, "结果数量应不超过配置的最大值");
        
        System.out.println("✓ 基本搜索测试通过");
        System.out.println("  搜索关键词: JTreg Java测试框架");
        System.out.println("  搜索结果数量: " + results.size());
        
        // 验证每个搜索结果的基本字段
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            SearchResult result = results.get(i);
            assertNotNull(result.getName(), "标题不应为空");
            assertNotNull(result.getUrl(), "URL不应为空");
            assertTrue(result.getRank() > 0, "排名应大于0");
            
            System.out.println("  [" + result.getRank() + "] " + result.getName());
            System.out.println("      " + result.getUrl());
            if (result.getSiteName() != null) {
                System.out.println("      网站: " + result.getSiteName());
            }
        }
    }
    
    @Test
    @DisplayName("6. 时间范围搜索测试")
    @EnabledIf("hasApiKey")
    void testFreshnessSearch() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        SearchConfig weekConfig = new SearchConfig()
                .setApiKey(apiKey)
                .setFreshness(Freshness.ONE_WEEK)
                .setSummary(false)
                .setCount(3)
                .setEnableLogging(false);
        
        WebSearch weekSearch = new WebSearch(weekConfig);
        List<SearchResult> results = weekSearch.search("OpenJDK新版本");
        
        assertNotNull(results);
        assertTrue(results.size() <= 3, "结果数量应不超过3");
        
        System.out.println("✓ 时间范围搜索测试通过");
        System.out.println("  搜索范围: 一周内");
        System.out.println("  搜索关键词: OpenJDK新版本");
        System.out.println("  结果数量: " + results.size());
        
        for (SearchResult result : results) {
            System.out.println("  - " + result.getName());
            System.out.println("    " + result.getUrl());
            if (result.getDatePublished() != null) {
                System.out.println("    发布时间: " + result.getDatePublished());
            }
        }
    }
    
    @Test
    @DisplayName("7. 网站搜索测试")
    @EnabledIf("hasApiKey")
    void testSiteSearch() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        List<SearchResult> results = webSearch.searchSite("JTreg documentation", "openjdk.org");
        
        assertNotNull(results);
        
        System.out.println("✓ 网站搜索测试通过");
        System.out.println("  搜索网站: openjdk.org");
        System.out.println("  搜索关键词: JTreg documentation");
        System.out.println("  结果数量: " + results.size());
        
        for (SearchResult result : results) {
            System.out.println("  - " + result.getName());
            System.out.println("    " + result.getUrl());
            if (result.getSiteName() != null) {
                System.out.println("    网站: " + result.getSiteName());
            }
            
            // 验证结果确实来自指定网站
            assertTrue(result.getUrl().contains("openjdk.org") || 
                      (result.getSiteName() != null && result.getSiteName().contains("openjdk")),
                      "搜索结果应来自指定网站");
        }
    }
    
    @Test
    @DisplayName("8. 排除网站搜索测试")
    @EnabledIf("hasApiKey")
    void testExcludeSiteSearch() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        List<SearchResult> results = webSearch.searchExcluding("Java testing", "stackoverflow.com", "github.com");
        
        assertNotNull(results);
        
        System.out.println("✓ 排除网站搜索测试通过");
        System.out.println("  搜索关键词: Java testing");
        System.out.println("  排除网站: stackoverflow.com, github.com");
        System.out.println("  结果数量: " + results.size());
        
        for (SearchResult result : results) {
            System.out.println("  - " + result.getName());
            System.out.println("    " + result.getUrl());
            if (result.getSiteName() != null) {
                System.out.println("    网站: " + result.getSiteName());
            }
            
            // 验证结果不包含被排除的网站
            assertFalse(result.getUrl().contains("stackoverflow.com"), 
                       "结果不应包含被排除的网站");
            assertFalse(result.getUrl().contains("github.com"), 
                       "结果不应包含被排除的网站");
        }
    }
    
    @Test
    @DisplayName("9. 图片搜索测试")
    @EnabledIf("hasApiKey")
    void testImageSearch() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        List<ImageResult> results = webSearch.searchImages("JTreg test framework", 3);
        
        assertNotNull(results);
        
        System.out.println("✓ 图片搜索测试通过");
        System.out.println("  搜索关键词: JTreg test framework");
        System.out.println("  图片结果数量: " + results.size());
        
        for (ImageResult result : results) {
            assertNotNull(result.getContentUrl(), "图片URL不应为空");
            assertTrue(result.getWidth() >= 0, "图片宽度应大于等于0");
            assertTrue(result.getHeight() >= 0, "图片高度应大于等于0");
            
            System.out.println("  图片名称: " + result.getName());
            System.out.println("  内容URL: " + result.getContentUrl());
            System.out.println("  缩略图URL: " + result.getThumbnailUrl());
            System.out.println("  尺寸: " + result.getWidth() + "x" + result.getHeight());
            System.out.println("  来源页面: " + result.getHostPageUrl());
            System.out.println("  ---");
        }
    }
    
    @Test
    @DisplayName("10. 搜索摘要测试")
    @EnabledIf("hasApiKey")
    void testSearchSummary() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        SearchSummary summary = webSearch.getSearchSummary("OpenJDK");
        
        assertNotNull(summary);
        assertNotNull(summary.getOriginalQuery());
        assertEquals("OpenJDK", summary.getOriginalQuery());
        assertTrue(summary.getTotalEstimatedMatches() >= 0);
        assertTrue(summary.getActualResults() >= 0);
        assertNotNull(summary.getTopDomains());
        
        System.out.println("✓ 搜索摘要测试通过");
        System.out.println("  原始查询: " + summary.getOriginalQuery());
        System.out.println("  估计匹配数: " + summary.getTotalEstimatedMatches());
        System.out.println("  实际结果数: " + summary.getActualResults());
        System.out.println("  主要域名: " + summary.getTopDomains());
        System.out.println("  结果被过滤: " + summary.isSomeResultsRemoved());
        
        String combinedSnippets = summary.getCombinedSnippets();
        if (combinedSnippets != null && combinedSnippets.length() > 100) {
            System.out.println("  组合摘要: " + combinedSnippets.substring(0, 100) + "...");
        } else {
            System.out.println("  组合摘要: " + combinedSnippets);
        }
    }
    
    @Test
    @DisplayName("11. 静态方法测试")
    @EnabledIf("hasApiKey")
    void testStaticMethods() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        // 测试快速搜索方法
        List<SearchResult> results1 = WebSearch.quickSearch("Java", apiKey);
        assertNotNull(results1);
        assertTrue(results1.size() <= 10, "默认结果数量应不超过10");
        
        List<SearchResult> results2 = WebSearch.quickSearch("Java", apiKey, 3);
        assertNotNull(results2);
        assertTrue(results2.size() <= 3, "指定结果数量应不超过3");
        
        System.out.println("✓ 静态方法测试通过");
        System.out.println("  快速搜索1结果数: " + results1.size());
        System.out.println("  快速搜索2结果数: " + results2.size());
        
        for (int i = 0; i < Math.min(2, results2.size()); i++) {
            SearchResult result = results2.get(i);
            System.out.println("  - " + result.getName());
            System.out.println("    " + result.getUrl());
        }
    }
    
    @Test
    @DisplayName("12. 包含特定网站测试")
    @EnabledIf("hasApiKey")
    void testIncludeSpecificSites() {
        Assumptions.assumeTrue(hasApiKey(), "需要API Key才能执行网络搜索测试");
        
        SearchConfig includeConfig = new SearchConfig()
                .setApiKey(apiKey)
                .setInclude("oracle.com|openjdk.org")
                .setCount(3)
                .setEnableLogging(false);
        
        WebSearch includeSearch = new WebSearch(includeConfig);
        List<SearchResult> results = includeSearch.search("Java documentation");
        
        assertNotNull(results);
        
        System.out.println("✓ 包含特定网站测试通过");
        System.out.println("  包含网站: oracle.com|openjdk.org");
        System.out.println("  搜索关键词: Java documentation");
        System.out.println("  结果数量: " + results.size());
        
        for (SearchResult result : results) {
            System.out.println("  - " + result.getName());
            System.out.println("    " + result.getUrl());
            if (result.getSiteName() != null) {
                System.out.println("    网站: " + result.getSiteName());
            }
            
            // 验证结果来自指定的网站
            assertTrue(result.getUrl().contains("oracle.com") || 
                      result.getUrl().contains("openjdk.org") ||
                      (result.getSiteName() != null && 
                       (result.getSiteName().contains("oracle") || result.getSiteName().contains("openjdk"))),
                      "搜索结果应来自指定的网站");
        }
    }
    
    @Test
    @DisplayName("13. 搜索参数边界测试")
    void testSearchParameterBounds() {
        if (!hasApiKey()) {
            System.out.println("⚠ 跳过网络测试，仅测试参数验证");
        }
        
        // 测试空查询
        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () -> {
            webSearch.search("");
        });
        assertTrue(exception1.getMessage().contains("搜索关键词不能为空"));
        
        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () -> {
            webSearch.search(null);
        });
        assertTrue(exception2.getMessage().contains("搜索关键词不能为空"));
        
        // 测试count参数边界
        SearchConfig boundConfig = new SearchConfig()
                .setApiKey("test-key")
                .setCount(100); // 超过最大值50
        
        assertEquals(50, boundConfig.getCount(), "count应被限制在50以内");
        
        SearchConfig boundConfig2 = new SearchConfig()
                .setApiKey("test-key")
                .setCount(-1); // 小于最小值1
        
        assertEquals(1, boundConfig2.getCount(), "count应被限制在1以上");
        
        System.out.println("✓ 搜索参数边界测试通过");
    }
    
    @Test
    @DisplayName("14. 兼容性方法测试")
    void testCompatibilityMethods() {
        SearchResult result = new SearchResult("测试标题", "测试摘要", "http://example.com", 1);
        
        // 测试新旧方法的兼容性
        assertEquals(result.getName(), result.getTitle());
        
        System.out.println("✓ 兼容性方法测试通过");
        System.out.println("  getName(): " + result.getName());
        System.out.println("  getTitle(): " + result.getTitle());
    }
    
    /**
     * 检查是否有API Key的辅助方法
     */
    static boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}