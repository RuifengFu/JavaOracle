package edu.tju.ista.llm4test.llm.tools;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.ins.InsExtension;
import com.vladsch.flexmark.ext.superscript.SuperscriptExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.http.HttpHeaders;
import java.util.regex.Matcher;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 智能网页内容提取器 - 静态优先，动态降级策略
 * 根据网站类型自动选择最优提取方式，大幅提高成功率和性能
 */
public class WebContentExtractor implements Tool<String> {
    private static final Logger logger = Logger.getLogger(WebContentExtractor.class.getName());
    
    // 用户代理字符串
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
    
    // 超时配置
    private static final int HTTP_TIMEOUT_SECONDS = 15;
    private static final int PLAYWRIGHT_WAIT_MS = 8000;
    private static final int PLAYWRIGHT_NAVIGATION_TIMEOUT = 12000;
    
    // 重试配置
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500;
    
    // 内容质量阈值
    private static final int MIN_CONTENT_LENGTH = 100; // 最小有效内容长度
    private static final int MIN_PARAGRAPH_COUNT = 1;  // 最小段落数
    private static final int MAX_CONTENT_LENGTH = 50000; // 最大内容长度
    
    // HTTP客户端（用于静态页面）
    private final HttpClient httpClient;
    // Markdown转换器
    private final FlexmarkHtmlConverter markdownConverter;
    // Playwright实例（延迟初始化）
    private Playwright playwright;
    private Browser browser;
    private final AtomicBoolean isPlaywrightInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantLock playwrightInitLock = new ReentrantLock();
    
    // 缓存已访问的URL以避免重复处理
    private final Map<String, ExtractionResult> urlCache = Collections.synchronizedMap(new LinkedHashMap<String, ExtractionResult>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ExtractionResult> eldest) {
            return size() > 1000; // 限制缓存大小
        }
    });
    
    // 需要动态渲染的网站模式
    private static final Set<String> DYNAMIC_SITE_PATTERNS = Set.of(
        "react", "angular", "vue", "spa", "app", "dashboard", "admin",
        "twitter.com", "facebook.com", "instagram.com", "linkedin.com",
        "medium.com", "dev.to", "stackoverflow.com"
    );
    
    // 静态内容网站模式
    private static final Set<String> STATIC_SITE_PATTERNS = Set.of(
        "blog", "news", "wiki", "doc", "documentation", "tutorial",
        "github.io", "readthedocs", "gitbook"
    );
    
    /**
     * 提取结果封装类
     */
    private static class ExtractionResult {
        final String content;
        final boolean isSuccess;
        final String method;
        final long timestamp;
        
        ExtractionResult(String content, boolean isSuccess, String method) {
            this.content = content;
            this.isSuccess = isSuccess;
            this.method = method;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 300000; // 5分钟过期
        }
    }
    
    public WebContentExtractor(boolean enablePlaywright) {
        // 初始化HTTP客户端
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
        
        // 初始化Markdown转换器
        MutableDataSet options = new MutableDataSet();
        options.set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                InsExtension.create(),
                SuperscriptExtension.create()
        ));
        markdownConverter = FlexmarkHtmlConverter.builder(options).build();
        
        // 如果启用Playwright，则在第一次需要时初始化
        // 这里不立即初始化，避免启动时的性能问题
    }
    
    /**
     * 智能内容提取 - 主入口方法
     */
    public String extractContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 检查缓存
        ExtractionResult cached = urlCache.get(url);
        if (cached != null && !cached.isExpired() && cached.isSuccess) {
            logger.info("使用缓存结果: " + url + " (缓存内容长度: " + cached.content.length() + ")");
            return cached.content;
        }
        
        logger.info("开始智能提取网页内容: " + url);
        
        // 1. 分析URL，决定提取策略
        ExtractionStrategy strategy = analyzeUrlAndDetermineStrategy(url);
        logger.info("选择提取策略: " + strategy + " for " + url);
        
        String content = null;
        String method = "";
        
        try {
            // 2. 根据策略执行提取
            switch (strategy) {
                case STATIC_FIRST:
                    content = extractWithStaticFirst(url);
                    method = "static_first";
                    break;
                case DYNAMIC_FIRST:
                    content = extractWithDynamicFirst(url);
                    method = "dynamic_first";
                    break;
                case STATIC_ONLY:
                    content = extractStaticContent(url);
                    method = "static_only";
                    break;
            }
            
            // 3. 验证内容质量
            if (isContentValid(content)) {
                logger.info("内容提取成功: " + url + " (方法: " + method + ", 长度: " + content.length() + ")");
                urlCache.put(url, new ExtractionResult(content, true, method));
                return content;
            } else {
                logger.warning("提取的内容质量不佳，尝试备用方法: " + url);
                // 尝试备用方法
                content = extractWithFallback(url, strategy);
                if (isContentValid(content)) {
                    logger.info("备用方法提取成功: " + url + " (长度: " + content.length() + ")");
                    urlCache.put(url, new ExtractionResult(content, true, "fallback"));
                    return content;
                }
            }
        } catch (Exception e) {
            logger.warning("内容提取异常: " + url + " - " + e.getMessage());
        }
        
        // 4. 所有方法都失败，返回基础信息
        String fallbackContent = "# 网页内容提取失败\n\n无法提取网页内容，请检查URL是否可访问：" + url;
        urlCache.put(url, new ExtractionResult(fallbackContent, false, "failed"));
        return fallbackContent;
    }
    
    /**
     * 分析URL并确定提取策略
     */
    private ExtractionStrategy analyzeUrlAndDetermineStrategy(String url) {
        String lowerUrl = url.toLowerCase();
        
        // 检查是否为明显的静态内容网站
        for (String pattern : STATIC_SITE_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return ExtractionStrategy.STATIC_FIRST;
            }
        }
        
        // 检查是否为明显的动态网站
        for (String pattern : DYNAMIC_SITE_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return ExtractionStrategy.DYNAMIC_FIRST;
            }
        }
        
        // 根据域名特征判断
        if (lowerUrl.contains("github.com") || lowerUrl.contains("stackoverflow.com") || 
            lowerUrl.contains("reddit.com") || lowerUrl.contains("medium.com")) {
            return ExtractionStrategy.DYNAMIC_FIRST;
        }
        
        // 文档、新闻、博客类网站优先静态
        if (lowerUrl.contains("doc") || lowerUrl.contains("blog") || 
            lowerUrl.contains("news") || lowerUrl.contains("wiki") ||
            lowerUrl.contains("oracle.com") || lowerUrl.contains("baeldung.com")) {
            return ExtractionStrategy.STATIC_FIRST;
        }
        
        // 默认策略：静态优先
        return ExtractionStrategy.STATIC_FIRST;
    }
    
    /**
     * 静态优先策略
     */
    private String extractWithStaticFirst(String url) {
        // 先尝试静态提取
        String staticContent = extractStaticContent(url);
        if (isContentValid(staticContent)) {
            return staticContent;
        }
        
        // 静态提取失败或内容不够，尝试动态提取
        logger.info("静态提取内容不足，尝试动态提取: " + url);
        return extractDynamicContent(url, null);
    }
    
    /**
     * 动态优先策略
     */
    private String extractWithDynamicFirst(String url) {
        // 先尝试动态提取
        try {
            String dynamicContent = extractDynamicContent(url, null);
            if (isContentValid(dynamicContent)) {
                return dynamicContent;
            }
        } catch (Exception e) {
            logger.warning("动态提取失败，回退到静态提取: " + url + " - " + e.getMessage());
        }
        
        // 动态提取失败，尝试静态提取
        return extractStaticContent(url);
    }
    
    /**
     * 备用提取方法
     */
    private String extractWithFallback(String url, ExtractionStrategy originalStrategy) {
        try {
            if (originalStrategy == ExtractionStrategy.STATIC_FIRST) {
                // 原来是静态优先，现在尝试动态
                return extractDynamicContent(url, null);
            } else {
                // 原来是动态优先，现在尝试静态
                return extractStaticContent(url);
            }
        } catch (Exception e) {
            logger.warning("备用提取方法也失败: " + url + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 改进的静态内容提取
     */
    public String extractStaticContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        int retryCount = 0;
        while (retryCount <= MAX_RETRIES) {
            try {
                logger.info("开始静态内容提取: " + url + " (尝试 " + (retryCount + 1) + "/" + (MAX_RETRIES + 1) + ")");
                
                // 创建HTTP请求
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept-Encoding", "gzip, deflate")
                        .header("Cache-Control", "no-cache")
                        .header("Upgrade-Insecure-Requests", "1")
                        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                        .GET()
                        .build();
                
                // 发送请求并获取响应 - 使用字节处理器以便手动处理编码
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                
                // 检查状态码
                if (response.statusCode() != 200) {
                    if (retryCount < MAX_RETRIES && (response.statusCode() == 503 || response.statusCode() == 502)) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    logger.warning("HTTP错误: " + response.statusCode() + " for URL: " + url);
                    return "页面获取失败: HTTP " + response.statusCode();
                }
                
                // 处理响应体编码
                byte[] bodyBytes = response.body();
                if (bodyBytes == null || bodyBytes.length == 0) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                // 检测并应用正确的字符编码
                String html = decodeResponseBody(bodyBytes, response.headers());
                if (html == null || html.trim().isEmpty()) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                Document doc = Jsoup.parse(html, url);
                
                // 改进的内容提取
                String extractedContent = extractMainContentAdvanced(doc, url);
                
                logger.info("静态内容提取完成: " + url + " (长度: " + extractedContent.length() + ")");
                return extractedContent;
                
            } catch (IOException | InterruptedException | URISyntaxException e) {
                logger.warning("静态页面获取失败 (尝试 " + (retryCount + 1) + "): " + e.getMessage());
                if (retryCount < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    retryCount++;
                } else {
                    return "页面获取失败: " + e.getMessage();
                }
            }
        }
        
        return "页面获取失败: 超过最大重试次数";
    }
    
    /**
     * 改进的动态内容提取
     */
    public String extractDynamicContent(String url, String waitSelector) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 确保Playwright已初始化
        if (!isPlaywrightInitialized.get()) {
            initializePlaywright();
        }
        
        if (browser == null) {
            logger.warning("Playwright初始化失败，回退到静态提取");
            return extractStaticContent(url);
        }
        
        BrowserContext context = null;
        Page page = null;
        
        try {
            logger.info("开始动态内容提取: " + url);
            
            // 创建页面上下文
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setHasTouch(false)
                    .setLocale("en-US")
                    .setViewportSize(1920, 1080)
                    .setIgnoreHTTPSErrors(true)
                    .setJavaScriptEnabled(true)
                    .setBypassCSP(true));
            
            // 创建新页面
            page = context.newPage();
            
            // 设置超时
            page.setDefaultTimeout(PLAYWRIGHT_WAIT_MS);
            page.setDefaultNavigationTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT);
            
            // 导航到目标URL
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT));
            
            // 检查响应状态
            if (response != null && response.status() >= 400) {
                logger.warning("页面响应错误: " + response.status() + " for URL: " + url);
                return "页面加载失败: HTTP " + response.status();
            }
            
            // 等待页面加载
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                        .setTimeout(PLAYWRIGHT_WAIT_MS));
            } catch (PlaywrightException e) {
                logger.info("网络空闲等待超时，继续处理: " + url);
            }
            
            // 等待特定元素（如果指定）
            if (waitSelector != null && !waitSelector.isEmpty()) {
                try {
                    page.waitForSelector(waitSelector, 
                            new Page.WaitForSelectorOptions().setTimeout(PLAYWRIGHT_WAIT_MS));
                } catch (PlaywrightException e) {
                    logger.info("等待选择器超时，继续处理: " + waitSelector);
                }
            }
            
            // 等待内容加载
            page.waitForTimeout(2000);
            
            // 获取页面内容
            String html = page.content();
            String title = page.title();
            
            if (html == null || html.trim().isEmpty()) {
                return "页面内容为空";
            }
            
            // 使用Jsoup解析HTML
            Document doc = Jsoup.parse(html, url);
            
            // 提取内容
            String extractedContent = extractMainContentAdvanced(doc, url);
            
            logger.info("动态内容提取完成: " + url + " (长度: " + extractedContent.length() + ")");
            return extractedContent;
            
        } catch (Exception e) {
            logger.warning("动态页面提取失败: " + url + " - " + e.getMessage());
            return "动态页面提取失败: " + e.getMessage();
        } finally {
            // 确保资源被正确清理
            if (page != null) {
                try {
                    page.close();
                } catch (Exception e) {
                    logger.warning("关闭页面失败: " + e.getMessage());
                }
            }
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    logger.warning("关闭上下文失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 高级内容提取算法
     */
    private String extractMainContentAdvanced(Document doc, String url) {
        // 1. 清理无用元素
        cleanDocument(doc);
        
        // 2. 多策略主内容提取
        Element mainContent = findMainContentAdvanced(doc);
        
        // 3. 提取标题
        String title = extractTitle(doc);
        
        // 4. 转换为Markdown
        String cleanedHtml = mainContent != null ? mainContent.outerHtml() : doc.body().html();
        String markdown = htmlToMarkdown(cleanedHtml, title);
        
        // 5. 后处理
        return postProcessMarkdown(markdown);
    }
    
    /**
     * 改进的文档清理
     */
    private void cleanDocument(Document doc) {
        // 移除脚本、样式等
        doc.select("script, style, svg, iframe, noscript, object, embed, video, audio").remove();
        
        // 移除广告和导航元素
        doc.select("nav, footer, .footer, .header, header, .navigation, .sidebar, .menu, .ad, "
                + ".advertisement, .banner, .cookie-notice, .popup, .modal, .overlay, "
                + ".social-share, .share-buttons, .comments, .comment-section, "
                + "[role=complementary], [role=banner], [role=navigation], [role=contentinfo], "
                + ".breadcrumb, .pagination, .related-posts, .sidebar-widget").remove();
        
        // 移除常见的垃圾类名
        doc.select("[class*=advert], [class*=banner], [class*=cookie], [class*=popup], "
                + "[class*=overlay], [class*=newsletter], [class*=social], [class*=share], "
                + "[id*=cookie], [id*=banner], [id*=ad], [id*=popup], "
                + "[data-ad], [data-advertisement]").remove();
        
        // 移除隐藏元素
        doc.select("[style*=display:none], [style*=visibility:hidden], .hidden, .invisible, "
                + "[aria-hidden=true]").remove();
        
        // 移除空元素
        Elements emptyElements = doc.select("p:empty, div:empty, span:empty, h1:empty, h2:empty, h3:empty, h4:empty, h5:empty, h6:empty");
        emptyElements.remove();
    }
    
    /**
     * 高级主内容查找算法
     */
    private Element findMainContentAdvanced(Document doc) {
        // 策略1: 语义化标签
        Element semanticMain = findSemanticMainContent(doc);
        if (semanticMain != null && calculateContentQuality(semanticMain) > 50) {
            return semanticMain;
        }
        
        // 策略2: 常见的内容容器
        Element containerMain = findContainerMainContent(doc);
        if (containerMain != null && calculateContentQuality(containerMain) > 50) {
            return containerMain;
        }
        
        // 策略3: 基于内容密度的分析
        Element densityMain = findMainContentByDensity(doc);
        if (densityMain != null && calculateContentQuality(densityMain) > 30) {
            return densityMain;
        }
        
        // 策略4: 最大文本块
        return findLargestTextBlock(doc);
    }
    
    /**
     * 查找语义化主内容
     */
    private Element findSemanticMainContent(Document doc) {
        // HTML5语义化标签
        Element main = doc.selectFirst("main");
        if (main != null) return main;
        
        Element article = doc.selectFirst("article");
        if (article != null) return article;
        
        // role属性
        Element roleMain = doc.selectFirst("[role=main]");
        if (roleMain != null) return roleMain;
        
        Element roleArticle = doc.selectFirst("[role=article]");
        if (roleArticle != null) return roleArticle;
        
        return null;
    }
    
    /**
     * 查找容器主内容
     */
    private Element findContainerMainContent(Document doc) {
        String[] containerSelectors = {
            "#content", "#main", "#main-content", "#primary", "#main-container",
            ".content", ".main", ".main-content", ".primary", ".main-container",
            ".post", ".entry", ".blog-post", ".article", ".post-content", 
            ".entry-content", ".page-content", ".article-content", ".post-body",
            ".entry-body", ".content-body", ".story", ".body"
        };
        
        Element bestElement = null;
        int maxQuality = 0;
        
        for (String selector : containerSelectors) {
            Elements elements = doc.select(selector);
            for (Element element : elements) {
                int quality = calculateContentQuality(element);
                if (quality > maxQuality) {
                    maxQuality = quality;
                    bestElement = element;
                }
            }
        }
        
        return bestElement;
    }
    
    /**
     * 基于内容密度查找主内容
     */
    private Element findMainContentByDensity(Document doc) {
        Elements allDivs = doc.select("div, section, article");
        
        Element bestElement = null;
        double maxDensity = 0;
        
        for (Element div : allDivs) {
            double density = calculateTextDensity(div);
            if (density > maxDensity && calculateContentQuality(div) > 20) {
                maxDensity = density;
                bestElement = div;
            }
        }
        
        return bestElement;
    }
    
    /**
     * 查找最大文本块
     */
    private Element findLargestTextBlock(Document doc) {
        Elements allElements = doc.select("div, section, article, main");
        
        Element largest = null;
        int maxTextLength = 0;
        
        for (Element element : allElements) {
            int textLength = element.text().length();
            if (textLength > maxTextLength) {
                maxTextLength = textLength;
                largest = element;
            }
        }
        
        return largest != null ? largest : doc.body();
    }
    
    /**
     * 计算内容质量分数
     */
    private int calculateContentQuality(Element element) {
        int score = 0;
        
        // 基于文本长度
        int textLength = element.text().length();
        score += Math.min(textLength / 10, 100); // 最多100分
        
        // 基于段落数量
        int paragraphCount = element.select("p").size();
        score += paragraphCount * 10; // 每个段落10分
        
        // 基于标题数量
        int headingCount = element.select("h1, h2, h3, h4, h5, h6").size();
        score += headingCount * 5; // 每个标题5分
        
        // 基于列表数量
        int listCount = element.select("ul, ol").size();
        score += listCount * 5; // 每个列表5分
        
        // 基于链接密度（链接过多可能是导航区域）
        int linkCount = element.select("a").size();
        int linkTextLength = element.select("a").text().length();
        if (textLength > 0) {
            double linkDensity = (double) linkTextLength / textLength;
            if (linkDensity > 0.5) score -= 50; // 链接密度过高扣分
        }
        
        // 基于类名和ID（某些类名表明是主内容）
        String className = element.className().toLowerCase();
        String id = element.id().toLowerCase();
        if (className.contains("content") || className.contains("main") || className.contains("article") ||
            id.contains("content") || id.contains("main") || id.contains("article")) {
            score += 20;
        }
        
        return Math.max(0, score);
    }
    
    /**
     * 计算文本密度
     */
    private double calculateTextDensity(Element element) {
        String text = element.text();
        String html = element.html();
        
        if (html.length() == 0) return 0;
        
        return (double) text.length() / html.length();
    }
    
    /**
     * 改进的标题提取
     */
    private String extractTitle(Document doc) {
        // 优先级顺序尝试提取标题
        String title = null;
        
        // 1. 页面title标签
        title = doc.title();
        if (isValidTitle(title)) return cleanTitle(title);
        
        // 2. OpenGraph标题
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        if (ogTitle != null) {
            title = ogTitle.attr("content");
            if (isValidTitle(title)) return cleanTitle(title);
        }
        
        // 3. 第一个h1标签
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            title = h1.text();
            if (isValidTitle(title)) return cleanTitle(title);
        }
        
        // 4. 其他标题标签
        for (String tag : Arrays.asList("h2", "h3")) {
            Element heading = doc.selectFirst(tag);
            if (heading != null) {
                title = heading.text();
                if (isValidTitle(title)) return cleanTitle(title);
            }
        }
        
        return "提取的网页内容";
    }
    
    /**
     * 验证标题是否有效
     */
    private boolean isValidTitle(String title) {
        return title != null && 
               title.trim().length() > 3 && 
               title.trim().length() < 200 &&
               !title.toLowerCase().contains("404") &&
               !title.toLowerCase().contains("error");
    }
    
    /**
     * 清理标题
     */
    private String cleanTitle(String title) {
        return title.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\r\\n\\t]", " ");
    }
    
    /**
     * 改进的HTML到Markdown转换
     */
    private String htmlToMarkdown(String html, String title) {
        // 转换HTML为Markdown
        String markdown = markdownConverter.convert(html);
        
        // 添加标题
        if (title != null && !title.isEmpty()) {
            markdown = "# " + title + "\n\n" + markdown;
        }
        
        return markdown;
    }
    
    /**
     * Markdown后处理
     */
    private String postProcessMarkdown(String markdown) {
        if (markdown == null) return "";
        
        // 清理多余的空行
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");
        
        // 移除行尾空格
        markdown = markdown.replaceAll("[ \\t]+\\n", "\n");
        
        // 确保列表项前有空行
        markdown = markdown.replaceAll("([^\\n])\\n([*\\-+] |\\d+\\. )", "$1\n\n$2");
        
        // 确保标题前有空行
        markdown = markdown.replaceAll("([^\\n])\\n(#{1,6} )", "$1\n\n$2");
        
        // 限制最终内容长度
        if (markdown.length() > MAX_CONTENT_LENGTH) {
            markdown = markdown.substring(0, MAX_CONTENT_LENGTH) + "\n\n...(内容已截断)";
        }
        
        return markdown.trim();
    }
    
    /**
     * 解码HTTP响应体，正确处理字符编码和压缩
     */
    private String decodeResponseBody(byte[] bodyBytes, HttpHeaders headers) {
        // 1. 检查并处理压缩编码
        byte[] decompressedBytes = decompressIfNeeded(bodyBytes, headers);
        
        // 2. 从Content-Type头中提取编码
        String charset = extractCharsetFromHeaders(headers);
        
        // 3. 如果没有找到编码，尝试从HTML内容中检测
        if (charset == null) {
            charset = detectCharsetFromHtml(decompressedBytes);
        }
        
        // 4. 使用检测到的编码或默认UTF-8解码
        if (charset == null) {
            charset = "UTF-8";
        }
        
        try {
            return new String(decompressedBytes, charset);
        } catch (Exception e) {
            logger.warning("使用编码 " + charset + " 解码失败，回退到UTF-8: " + e.getMessage());
            return new String(decompressedBytes, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * 根据Content-Encoding头解压缩内容
     */
    private byte[] decompressIfNeeded(byte[] bodyBytes, HttpHeaders headers) {
        Optional<String> contentEncoding = headers.firstValue("content-encoding");
        if (contentEncoding.isPresent()) {
            String encoding = contentEncoding.get().toLowerCase();
            logger.info("检测到内容编码: " + encoding);
            
            if ("gzip".equals(encoding)) {
                try {
                    return decompressGzip(bodyBytes);
                } catch (Exception e) {
                    logger.warning("Gzip解压失败: " + e.getMessage());
                    return bodyBytes;
                }
            } else if ("deflate".equals(encoding)) {
                try {
                    return decompressDeflate(bodyBytes);
                } catch (Exception e) {
                    logger.warning("Deflate解压失败: " + e.getMessage());
                    return bodyBytes;
                }
            }
        }
        return bodyBytes;
    }
    
    /**
     * Gzip解压缩
     */
    private byte[] decompressGzip(byte[] compressedData) throws IOException {
        try (java.util.zip.GZIPInputStream gzipInputStream = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(compressedData));
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            logger.info("Gzip解压成功，原始大小: " + compressedData.length + ", 解压后大小: " + outputStream.size());
            return outputStream.toByteArray();
        }
    }
    
    /**
     * Deflate解压缩
     */
    private byte[] decompressDeflate(byte[] compressedData) throws IOException {
        try (java.util.zip.InflaterInputStream inflaterInputStream = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(compressedData));
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inflaterInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            logger.info("Deflate解压成功，原始大小: " + compressedData.length + ", 解压后大小: " + outputStream.size());
            return outputStream.toByteArray();
        }
    }
    
    /**
     * 从HTTP头中提取字符编码
     */
    private String extractCharsetFromHeaders(HttpHeaders headers) {
        Optional<String> contentType = headers.firstValue("content-type");
        if (contentType.isPresent()) {
            String ct = contentType.get().toLowerCase();
            Pattern charsetPattern = Pattern.compile("charset\\s*=\\s*([^;\\s]+)");
            Matcher matcher = charsetPattern.matcher(ct);
            if (matcher.find()) {
                String charset = matcher.group(1).trim().replace("\"", "");
                logger.info("从响应头检测到编码: " + charset);
                return charset;
            }
        }
        return null;
    }
    
    /**
     * 从HTML内容中检测字符编码
     */
    private String detectCharsetFromHtml(byte[] bodyBytes) {
        try {
            // 使用ASCII解码前1024字节来查找meta标签
            String htmlStart = new String(bodyBytes, 0, Math.min(1024, bodyBytes.length), StandardCharsets.US_ASCII);
            
            // 查找meta charset标签
            Pattern metaCharsetPattern = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = metaCharsetPattern.matcher(htmlStart);
            if (matcher.find()) {
                String charset = matcher.group(1).trim();
                logger.info("从HTML meta标签检测到编码: " + charset);
                return charset;
            }
            
            // 查找Content-Type meta标签
            Pattern metaContentTypePattern = Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']?[^\"'>]*charset\\s*=\\s*([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);
            matcher = metaContentTypePattern.matcher(htmlStart);
            if (matcher.find()) {
                String charset = matcher.group(1).trim();
                logger.info("从HTML Content-Type meta标签检测到编码: " + charset);
                return charset;
            }
        } catch (Exception e) {
            logger.warning("从HTML检测编码失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 验证内容是否有效
     */
    private boolean isContentValid(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        // 检查长度
        if (content.length() < MIN_CONTENT_LENGTH) {
            return false;
        }
        
        // 检查是否包含有意义的内容
        String textContent = content.replaceAll("#", "").replaceAll("\\*", "").replaceAll("\\[", "").replaceAll("\\]", "").trim();
        if (textContent.length() < MIN_CONTENT_LENGTH / 3) {
            return false;
        }
        
        // 检查段落数量 - 更宽松的判断
        long paragraphCount = content.lines().filter(line -> 
            line.trim().length() > 10 && !line.trim().startsWith("#")).count();
        
        boolean hasValidContent = paragraphCount >= MIN_PARAGRAPH_COUNT;
        
        // 如果内容很长，即使段落数少也认为有效
        if (content.length() > 1000) {
            hasValidContent = true;
        }
        
        return hasValidContent;
    }
    
    /**
     * 延迟初始化Playwright
     */
    private void initializePlaywright() {
        if (isPlaywrightInitialized.get() || isClosed.get()) {
            return;
        }
        
        playwrightInitLock.lock();
        try {
            if (isPlaywrightInitialized.get() || isClosed.get()) {
                return;
            }
            
            logger.info("开始初始化Playwright");
            
            try {
                playwright = Playwright.create();
                
                // 配置浏览器选项
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(Arrays.asList(
                                "--disable-blink-features=AutomationControlled",
                                "--disable-dev-shm-usage",
                                "--disable-gpu",
                                "--no-sandbox",
                                "--disable-setuid-sandbox",
                                "--disable-web-security"
                        ))
                        .setTimeout(15000);
                
                browser = playwright.chromium().launch(launchOptions);
                isPlaywrightInitialized.set(true);
                
                logger.info("Playwright初始化完成");
            } catch (Exception e) {
                logger.severe("Playwright初始化失败: " + e.getMessage());
                cleanup();
            }
        } finally {
            playwrightInitLock.unlock();
        }
    }
    
    /**
     * 截图功能
     */
    public boolean takeScreenshot(String url, String outputPath) {
        if (!isPlaywrightInitialized.get()) {
            initializePlaywright();
        }
        
        if (browser == null) {
            logger.warning("Playwright未初始化，无法截图");
            return false;
        }
        
        BrowserContext context = null;
        Page page = null;
        
        try {
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setViewportSize(1920, 1080));
            
            page = context.newPage();
            page.navigate(url);
            page.waitForTimeout(3000);
            
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(outputPath))
                    .setFullPage(true));
            
            logger.info("截图成功: " + outputPath);
            return true;
            
        } catch (Exception e) {
            logger.warning("截图失败: " + e.getMessage());
            return false;
        } finally {
            if (page != null) page.close();
            if (context != null) context.close();
        }
    }
    
    /**
     * 保存到文件
     */
    public boolean saveToFile(String content, String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            
            Files.writeString(path, content, StandardCharsets.UTF_8);
            logger.info("文件保存成功: " + filePath);
            return true;
        } catch (IOException e) {
            logger.warning("保存文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 批量处理
     */
    public int processBatch(List<String> urls, String outputDir) {
        int successCount = 0;
        
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                logger.info("处理 " + (i+1) + "/" + urls.size() + ": " + url);
                
                String content = extractContent(url);
                
                String fileName = generateSafeFileName(url);
                String filePath = outputDir + "/" + fileName + ".md";
                
                if (saveToFile(content, filePath)) {
                    successCount++;
                }
                
                // 避免过快请求
                Thread.sleep(1000);
                
            } catch (Exception e) {
                logger.warning("处理URL失败: " + url + " - " + e.getMessage());
            }
        }
        
        logger.info("批量处理完成，成功处理 " + successCount + "/" + urls.size() + " 个URL");
        return successCount;
    }
    
    /**
     * 生成安全的文件名
     */
    private String generateSafeFileName(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String path = uri.getPath();
            
            String fileName = host + path.replace("/", "_");
            fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_").replaceAll("_{2,}", "_");
            
            if (fileName.length() > 100) {
                fileName = fileName.substring(0, 100);
            }
            
            return fileName;
        } catch (URISyntaxException e) {
            return "page_" + System.currentTimeMillis();
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        try {
            if (browser != null) {
                browser.close();
                browser = null;
            }
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        } catch (Exception e) {
            logger.warning("清理资源时出错: " + e.getMessage());
        }
        isPlaywrightInitialized.set(false);
    }
    
    /**
     * 关闭提取器
     */
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            logger.info("开始关闭WebContentExtractor");
            
            playwrightInitLock.lock();
            try {
                cleanup();
                urlCache.clear();
                logger.info("WebContentExtractor已关闭");
            } finally {
                playwrightInitLock.unlock();
            }
        }
    }
    
    // Tool接口实现
    @Override
    public String getName() {
        return "extract_web_content";
    }

    @Override
    public String getDescription() {
        return "智能网页内容提取器，自动选择最优提取策略，支持静态和动态内容提取，大幅提高成功率和性能。";
    }

    @Override
    public List<String> getParameters() {
        return List.of("url");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("url", "要提取内容的网页URL");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("url", "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("url") || !(args.get("url") instanceof String url)) {
            return ToolResponse.failure("参数错误，必须提供 url 且其类型为 String");
        }
        return execute(url);
    }

    public ToolResponse<String> execute(String url) {
        try {
            String content = extractContent(url);
            return ToolResponse.success(content);
        } catch (Exception e) {
            logger.warning("执行Web内容提取失败: " + e.getMessage());
            return ToolResponse.failure("Failed to extract content: " + e.getMessage());
        }
    }
    
    /**
     * 提取策略枚举
     */
    private enum ExtractionStrategy {
        STATIC_FIRST,   // 静态优先
        DYNAMIC_FIRST,  // 动态优先
        STATIC_ONLY     // 仅静态
    }
}